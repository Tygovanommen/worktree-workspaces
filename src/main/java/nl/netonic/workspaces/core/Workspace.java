package nl.netonic.workspaces.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A workspace is one folder holding a {@code .workspace.json}, a {@code main/} folder with the
 * real clones, and one sibling folder per ticket with git worktrees of those clones.
 */
public final class Workspace {
  public static final String MAIN = "main";
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  private final Path root;
  private final WorkspaceConfig config;

  public Workspace(Path root, WorkspaceConfig config) {
    this.root = root.toAbsolutePath().normalize();
    this.config = config;
  }

  public static boolean isWorkspaceRoot(Path dir) {
    return dir != null && Files.isRegularFile(dir.resolve(WorkspaceConfig.FILE_NAME));
  }

  public static Workspace load(Path root) throws IOException {
    return new Workspace(root, WorkspaceConfig.load(root.resolve(WorkspaceConfig.FILE_NAME)));
  }

  /** Finds the workspace that contains {@code path} (a ticket folder, a repo inside one, ...). */
  public static Workspace findContaining(Path path) {
    Path p = path == null ? null : path.toAbsolutePath().normalize();
    for (int depth = 0; p != null && depth < 4; depth++, p = p.getParent()) {
      if (isWorkspaceRoot(p)) {
        try {
          return load(p);
        } catch (IOException e) {
          return null;
        }
      }
    }
    return null;
  }

  /** Valid folder / repo / ticket name: letters, digits, dot, dash, underscore. */
  public static boolean isValidName(String name) {
    return name != null && NAME.matcher(name).matches() && !name.equals(".") && !name.equals("..");
  }

  /** Ticket key as typed: valid names separated by '/', e.g. LOGIN-42 or LOGIN-42/login-form. */
  public static boolean isValidTicketKey(String key) {
    if (key == null || key.isEmpty()) return false;
    for (String part : key.split("/", -1)) {
      if (!isValidName(part)) return false;
    }
    return true;
  }

  /** The folder (and tab) name of a ticket key: '/' becomes '-', so LOGIN-42/login-form → LOGIN-42-login-form. */
  public static String ticketFolderName(String key) {
    return key.replace('/', '-');
  }

  /** Workspace and repo name suggested for a cloned folder. */
  public record CloneNames(String workspace, String repo) {}

  /** billebly-api → billebly + api (split at the last '-' or '_'); without one, both are the folder name. */
  public static CloneNames suggestNames(String folder) {
    int cut = Math.max(folder.lastIndexOf('-'), folder.lastIndexOf('_'));
    if (cut <= 0 || cut == folder.length() - 1) return new CloneNames(folder, folder);
    return new CloneNames(folder.substring(0, cut), folder.substring(cut + 1));
  }

  public Path getRoot() { return root; }
  public WorkspaceConfig getConfig() { return config; }
  public String getName() { return config.getName(); }
  public Path mainDir() { return root.resolve(MAIN); }
  public Path ticketDir(String ticket) { return root.resolve(ticket); }
  public Path configFile() { return root.resolve(WorkspaceConfig.FILE_NAME); }

  public void saveConfig() throws IOException {
    config.save(configFile());
  }

  /** {@code main} first, then every other non-hidden folder, alphabetically. */
  public List<Ticket> tickets() {
    List<Ticket> result = new ArrayList<>();
    result.add(new Ticket(this, MAIN));
    if (!Files.isDirectory(root)) return result;
    try (Stream<Path> s = Files.list(root)) {
      s.filter(Files::isDirectory)
          .map(p -> p.getFileName().toString())
          .filter(n -> !n.startsWith(".") && !n.equalsIgnoreCase(MAIN))
          .filter(n -> looksLikeTicket(root.resolve(n)))
          .sorted(Comparator.comparing(String::toLowerCase))
          .forEach(n -> result.add(new Ticket(this, n)));
    } catch (IOException ignored) {
      // unreadable folder: show only main
    }
    return result;
  }

  /** A ticket folder has a .idea folder or at least one git checkout; other folders (docs, scripts) are ignored. */
  public static boolean looksLikeTicket(Path dir) {
    if (Files.isDirectory(dir.resolve(".idea"))) return true;
    try (Stream<Path> s = Files.list(dir)) {
      return s.anyMatch(Ticket::isGitDir);
    } catch (IOException e) {
      return false;
    }
  }

  public Ticket ticket(String name) {
    return new Ticket(this, name);
  }

  /** The ticket whose folder is (or contains) {@code path}, or null. */
  public Ticket ticketFor(Path path) {
    Path p = path.toAbsolutePath().normalize();
    if (!p.startsWith(root) || p.equals(root)) return null;
    String first = root.relativize(p).getName(0).toString();
    if (first.startsWith(".")) return null;
    return new Ticket(this, first);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Workspace w && w.root.equals(root);
  }

  @Override
  public int hashCode() {
    return root.hashCode();
  }

  @Override
  public String toString() {
    return getName() + " (" + root + ")";
  }
}
