package nl.netonic.workspaces.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** One ticket folder inside a workspace ({@code main} is the default ticket). */
public final class Ticket {
  private final Workspace workspace;
  private final String name;

  Ticket(Workspace workspace, String name) {
    this.workspace = workspace;
    this.name = name;
  }

  public Workspace getWorkspace() { return workspace; }
  public String getName() { return name; }
  public Path getDir() { return workspace.ticketDir(name); }
  public boolean isMain() { return Workspace.MAIN.equalsIgnoreCase(name); }
  public boolean exists() { return Files.isDirectory(getDir()); }

  /** Name shown in the window tab / title bar, e.g. "klant-x · LOGIN-42". */
  public String displayName() {
    return workspace.getName() + " · " + name;
  }

  /**
   * Every repo of the workspace as seen from this ticket: its own worktree, the clone in main,
   * or the main clone attached because this ticket doesn't touch that repo.
   * Repos found on disk but missing from the config are listed too.
   */
  public List<TicketRepo> repos() {
    if (workspace.isSingleRepo()) {
      Path dir = getDir();
      TicketRepo.Kind kind = isMain() ? TicketRepo.Kind.CLONE : isGitDir(dir) ? TicketRepo.Kind.WORKTREE : TicketRepo.Kind.MISSING;
      return List.of(new TicketRepo(workspace.singleRepoName(), dir, kind));
    }
    Set<String> names = new LinkedHashSet<>(workspace.getConfig().getRepos().keySet());
    names.addAll(gitFoldersIn(getDir()));
    if (!isMain()) names.addAll(gitFoldersIn(workspace.mainDir()));

    List<TicketRepo> result = new ArrayList<>();
    for (String repo : names) {
      Path own = getDir().resolve(repo);
      Path main = workspace.mainDir().resolve(repo);
      if (isGitDir(own)) {
        result.add(new TicketRepo(repo, own, isMain() ? TicketRepo.Kind.CLONE : TicketRepo.Kind.WORKTREE));
      } else if (!isMain() && isGitDir(main)) {
        result.add(new TicketRepo(repo, main, TicketRepo.Kind.ATTACHED));
      } else {
        result.add(new TicketRepo(repo, own, TicketRepo.Kind.MISSING));
      }
    }
    return result;
  }

  static boolean isGitDir(Path dir) {
    return Files.isDirectory(dir) && Files.exists(dir.resolve(".git"));
  }

  private static List<String> gitFoldersIn(Path dir) {
    List<String> out = new ArrayList<>();
    if (!Files.isDirectory(dir)) return out;
    try (Stream<Path> s = Files.list(dir)) {
      s.filter(Ticket::isGitDir).map(p -> p.getFileName().toString()).sorted().forEach(out::add);
    } catch (IOException ignored) {
      // ignore
    }
    return out;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Ticket t && t.getDir().equals(getDir());
  }

  @Override
  public int hashCode() {
    return getDir().hashCode();
  }

  @Override
  public String toString() {
    return displayName();
  }
}
