package nl.netonic.workspaces.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Symlinks next to the workspace, so tools that only look one folder deep (Herd, Valet, ...) find every repo:
 * <pre>
 *   ~/PhpstormProjects/klant-x-backend           -> klant-x/main/backend
 *   ~/PhpstormProjects/klant-x-login-42-backend  -> klant-x/LOGIN-42/backend
 * </pre>
 * Never replaces anything that is already there; only removes links into a finished ticket, or links of this
 * workspace whose target is gone.
 */
public final class Links {
  private Links() {}

  /** The folder the links go in: the workspace's parent. */
  public static Path dir(Workspace ws) {
    return ws.getRoot().getParent();
  }

  /** klant-x-backend for main, klant-x-login-42-backend for a ticket: lowercase letters, digits and '-' (a valid host name). */
  public static String name(Ticket ticket, String repo) {
    String ws = ticket.getWorkspace().getName();
    return clean(ticket.isMain() ? ws + "-" + repo : ws + "-" + ticket.getName() + "-" + repo);
  }

  private static String clean(String raw) {
    return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("-{2,}", "-")
        .replaceAll("^-|-$", "");
  }

  /** Links {@code <ticket>/<repo>}; returns the link's name, or null when it was already there or couldn't be made. */
  public static String create(Ticket ticket, String repo, WorkspaceOps.Log log) {
    Path parent = dir(ticket.getWorkspace());
    Path target = ticket.getDir().resolve(repo).toAbsolutePath().normalize();
    if (parent == null || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) return null;
    Path link = parent.resolve(name(ticket, repo));
    try {
      if (Files.isSymbolicLink(link)) {
        if (!target.equals(resolve(link))) log.warn(link + " already links to " + resolve(link) + "; left as it is");
        return null;
      }
      if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
        log.warn(link + " already exists; no link made for " + parent.relativize(target));
        return null;
      }
      // relative, so the links keep working when the whole projects folder moves
      Files.createSymbolicLink(link, parent.relativize(target));
      return link.getFileName().toString();
    } catch (IOException | UnsupportedOperationException e) {
      log.warn("Could not create link " + link + ": " + e.getMessage());
      return null;
    }
  }

  /** Links every repo of the ticket that is its own checkout (main's clones, a ticket's worktrees). */
  public static List<String> sync(Ticket ticket, WorkspaceOps.Log log) {
    List<String> made = new ArrayList<>();
    for (TicketRepo r : ticket.repos()) {
      boolean own = ticket.isMain() ? r.kind() == TicketRepo.Kind.CLONE : r.kind() == TicketRepo.Kind.WORKTREE;
      if (!own) continue;
      String name = create(ticket, r.name(), log);
      if (name != null) made.add(name);
    }
    return made;
  }

  /**
   * Removes this workspace's links whose target is gone (a ticket or repo deleted outside the plugin).
   * Only links named like ours ({@code klant-x-...}) that point into the workspace; returns their names.
   */
  public static List<String> tidy(Workspace ws, WorkspaceOps.Log log) {
    return removeOurs(ws, true, log);
  }

  /** Removes all of this workspace's links (links switched off); same naming and target checks as {@link #tidy}. */
  public static List<String> removeAll(Workspace ws, WorkspaceOps.Log log) {
    return removeOurs(ws, false, log);
  }

  private static List<String> removeOurs(Workspace ws, boolean onlyDangling, WorkspaceOps.Log log) {
    List<String> removed = new ArrayList<>();
    Path parent = dir(ws);
    if (parent == null || !Files.isDirectory(parent)) return removed;
    String prefix = clean(ws.getName()) + "-";
    List<Path> links;
    try (Stream<Path> s = Files.list(parent)) {
      links = s.filter(p -> p.getFileName().toString().startsWith(prefix) && Files.isSymbolicLink(p)).toList();
    } catch (IOException e) {
      log.warn("Could not list " + parent + ": " + e.getMessage());
      return removed;
    }
    for (Path link : links) {
      try {
        Path target = resolve(link);
        if (!target.startsWith(ws.getRoot()) || target.equals(ws.getRoot())) continue;
        if (onlyDangling && Files.exists(target)) continue;
        Files.delete(link); // the link itself, never its target
        removed.add(link.getFileName().toString());
      } catch (IOException e) {
        log.warn("Could not remove link " + link + ": " + e.getMessage());
      }
    }
    return removed;
  }

  /** Removes the link of {@code <ticket>/<repo>} if it points into the ticket; returns its name, or null. */
  public static String remove(Ticket ticket, String repo) throws IOException {
    Path parent = dir(ticket.getWorkspace());
    if (parent == null) return null;
    Path link = parent.resolve(name(ticket, repo));
    if (!Files.isSymbolicLink(link)) return null;
    if (!resolve(link).startsWith(ticket.getDir().toAbsolutePath().normalize())) return null;
    Files.delete(link); // deletes the link itself, never what it points to
    return link.getFileName().toString();
  }

  /** Where a link points, as an absolute path; works for links whose target is gone. */
  static Path resolve(Path link) throws IOException {
    return link.getParent().resolve(Files.readSymbolicLink(link)).toAbsolutePath().normalize();
  }
}
