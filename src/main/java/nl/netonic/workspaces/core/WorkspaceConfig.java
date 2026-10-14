package nl.netonic.workspaces.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 *   },
 *   "tickets": {
 *     "LOGIN-42-login-form": { "branch": "feature/LOGIN-42/login-form" }
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
  /** Per ticket folder: the branch it was created on, with any fields a newer version added. */
  private final LinkedHashMap<String, Map<String, Object>> tickets = new LinkedHashMap<>();
  /** Fields this version doesn't know (written by a newer one): kept as-is on save. */
  private final LinkedHashMap<String, Object> extra = new LinkedHashMap<>();
  private static final List<String> KNOWN = List.of("name", "branchPattern", "repos", "tickets");
  private static final List<String> KNOWN_REPO = List.of("url", "baseBranch", "copy", "adoptedFrom");

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

  /** The branch for a new ticket; {@code key} may contain '/' (e.g. LOGIN-42/login-form). */
  public String branchFor(String key) {
    return branchPattern.replace("{ticket}", key);
  }

  /** The branch ticket folder {@code ticket} was created on, else the one the pattern gives. */
  public String ticketBranch(String ticket) {
    Map<String, Object> t = tickets.get(ticket);
    String branch = t == null ? null : str(t.get("branch"), null);
    return branch != null ? branch : branchFor(ticket);
  }

  public void setTicketBranch(String ticket, String branch) {
    if (branch == null || branch.isBlank()) {
      Map<String, Object> t = tickets.get(ticket);
      if (t != null) t.remove("branch");
    } else {
      tickets.computeIfAbsent(ticket, k -> new LinkedHashMap<>()).put("branch", branch.trim());
    }
  }

  public void removeTicket(String ticket) {
    tickets.remove(ticket);
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
    keepUnknown(root, KNOWN, cfg.extra);
    if (root.get("repos") instanceof Map<?, ?> repos) {
      for (Map.Entry<?, ?> e : repos.entrySet()) {
        // the name becomes a folder under main/ and every ticket: no "../x" or "a/b"
        if (!Workspace.isValidName(String.valueOf(e.getKey()))) continue;
        RepoConfig rc = new RepoConfig();
        if (e.getValue() instanceof Map<?, ?> r) {
          keepUnknown(r, KNOWN_REPO, rc.extra);
          rc.setBaseBranch(str(r.get("baseBranch"), null));
          rc.setUrl(str(r.get("url"), null));
          rc.setAdoptedFrom(str(r.get("adoptedFrom"), null));
          if (r.get("copy") instanceof List<?> l) {
            List<String> copy = new ArrayList<>();
            for (Object o : l) if (o != null && !o.toString().isBlank()) copy.add(o.toString().trim());
            rc.setCopy(copy);
          }
        }
        cfg.repos.put(String.valueOf(e.getKey()), rc);
      }
    }
    if (root.get("tickets") instanceof Map<?, ?> ts) {
      for (Map.Entry<?, ?> e : ts.entrySet()) {
        if (!Workspace.isValidName(String.valueOf(e.getKey())) || !(e.getValue() instanceof Map<?, ?> t)) continue;
        Map<String, Object> copy = new LinkedHashMap<>();
        t.forEach((k, v) -> copy.put(String.valueOf(k), v));
        cfg.tickets.put(String.valueOf(e.getKey()), copy);
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
      if (rc.getAdoptedFrom() != null) r.put("adoptedFrom", rc.getAdoptedFrom());
      r.putAll(rc.extra);
      rs.put(e.getKey(), r);
    }
    root.put("repos", rs);
    if (!tickets.isEmpty()) {
      Map<String, Object> ts = new LinkedHashMap<>();
      tickets.forEach((k, v) -> ts.put(k, new LinkedHashMap<>(v)));
      root.put("tickets", ts);
    }
    root.putAll(extra);
    // write next to it and move over, so a crash never leaves a truncated config
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
    try {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void keepUnknown(Map<?, ?> from, List<String> known, Map<String, Object> into) {
    for (Map.Entry<?, ?> e : from.entrySet()) {
      String key = String.valueOf(e.getKey());
      if (!known.contains(key)) into.put(key, e.getValue());
    }
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
    /** Where an adopted checkout was before it moved into main/; Undo Workspace puts it back there. */
    private String adoptedFrom;
    private List<String> copy = new ArrayList<>(DEFAULT_COPY);
    private final LinkedHashMap<String, Object> extra = new LinkedHashMap<>();

    public String getBaseBranch() { return baseBranch; }
    public void setBaseBranch(String b) { baseBranch = b == null || b.isBlank() ? null : b.trim(); }

    public String getUrl() { return url; }
    public void setUrl(String u) { url = u == null || u.isBlank() ? null : u.trim(); }

    public String getAdoptedFrom() { return adoptedFrom; }
    public void setAdoptedFrom(String p) { adoptedFrom = p == null || p.isBlank() ? null : p.trim(); }

    public List<String> getCopy() { return copy; }
    public void setCopy(List<String> c) { copy = c == null ? new ArrayList<>() : new ArrayList<>(c); }
  }
}
