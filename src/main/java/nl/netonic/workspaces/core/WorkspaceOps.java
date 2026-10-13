package nl.netonic.workspaces.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** All file-system and git operations of the plugin. No IDE dependencies, so it is unit-testable. */
public final class WorkspaceOps {
  private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration CLONE_TIMEOUT = Duration.ofMinutes(15);

  /** Receives progress lines; implementations forward them to a progress indicator or a log. */
  public interface Log {
    void step(String text);

    void warn(String text);

    Log NONE = new Log() {
      @Override public void step(String text) {}
      @Override public void warn(String text) {}
    };
  }

  /** What to do for one repo when creating a ticket. */
  public record RepoChoice(String repo, boolean include, String baseBranch) {}

  private final GitRunner git;

  public WorkspaceOps(GitRunner git) {
    this.git = git;
  }

  public GitRunner git() {
    return git;
  }

  // ------------------------------------------------------------ workspaces

  public Workspace createWorkspace(Path parent, String name) throws IOException {
    if (!Workspace.isValidName(name)) throw new IOException("Invalid workspace name: " + name);
    Path root = parent.resolve(name);
    if (Workspace.isWorkspaceRoot(root)) throw new IOException(root + " already is a workspace");
    if (Files.exists(root) && !isEmptyDir(root)) {
      throw new IOException(root + " already exists and is not empty");
    }
    Files.createDirectories(root.resolve(Workspace.MAIN));
    WorkspaceConfig cfg = new WorkspaceConfig(name);
    cfg.save(root.resolve(WorkspaceConfig.FILE_NAME));
    return new Workspace(root, cfg);
  }

  /** Clones {@code url} into main/&lt;repoName&gt; and registers it. */
  public void addRepoByClone(Workspace ws, String repoName, String url, Log log) throws IOException, GitException {
    Path target = checkNewRepoTarget(ws, repoName);
    log.step("Cloning " + url);
    Files.createDirectories(ws.mainDir());
    git.run(ws.mainDir(), CLONE_TIMEOUT, "clone", "--", url, repoName).orThrow("git clone failed");
    registerRepo(ws, repoName, target, url);
  }

  /**
   * Moves an existing checkout into main/&lt;repoName&gt; and registers it.
   * Runs {@code git worktree repair} so worktrees made earlier keep working.
   */
  public void adoptRepo(Workspace ws, String repoName, Path existing, Log log) throws IOException, GitException {
    Path source = existing.toAbsolutePath().normalize();
    if (!Ticket.isGitDir(source)) throw new IOException(source + " is not a git checkout");
    if (Files.isRegularFile(source.resolve(".git"))) {
      throw new IOException(source + " is itself a worktree; adopt the main clone instead");
    }
    Workspace owner = Workspace.findContaining(source);
    if (owner != null) {
      // moving it would break that workspace's worktrees (and its main/ must never move)
      throw new IOException(source + " belongs to workspace " + owner.getName() + "; clone it by URL instead");
    }
    Path target = checkNewRepoTarget(ws, repoName);
    if (target.startsWith(source)) throw new IOException("Can't move a folder into itself");
    log.step("Moving " + source.getFileName() + " to " + ws.getRoot().relativize(target));
    Files.createDirectories(ws.mainDir());
    Files.move(source, target);
    GitRunner.Result repair = git.run(target, "worktree", "repair");
    if (!repair.ok()) log.warn("git worktree repair: " + repair.message());
    GitRunner.Result origin = git.run(target, "remote", "get-url", "origin");
    registerRepo(ws, repoName, target, origin.ok() ? origin.out() : null);
  }

  private Path checkNewRepoTarget(Workspace ws, String repoName) throws IOException {
    if (!Workspace.isValidName(repoName)) throw new IOException("Invalid repo name: " + repoName);
    Path target = ws.mainDir().resolve(repoName);
    if (Files.exists(target)) throw new IOException(ws.getRoot().relativize(target) + " already exists");
    return target;
  }

  private static final Object CONFIG_LOCK = new Object();

  /** Re-reads .workspace.json right before writing, so edits made meanwhile are kept. */
  private void registerRepo(Workspace ws, String repoName, Path checkout, String url) throws IOException {
    String base = detectDefaultBranch(checkout);
    synchronized (CONFIG_LOCK) {
      WorkspaceConfig onDisk = Files.isRegularFile(ws.configFile()) ? WorkspaceConfig.load(ws.configFile()) : ws.getConfig();
      WorkspaceConfig.RepoConfig rc = onDisk.getRepos().computeIfAbsent(repoName, k -> new WorkspaceConfig.RepoConfig());
      rc.setUrl(url);
      if (rc.getBaseBranch() == null) rc.setBaseBranch(base);
      onDisk.save(ws.configFile());
      ws.getConfig().getRepos().put(repoName, rc);
    }
  }

  /**
   * The remote's default branch: origin/HEAD, else asked from the remote, else main / master / develop
   * if one exists, else the branch currently checked out.
   */
  public String detectDefaultBranch(Path checkout) {
    GitRunner.Result head = git.run(checkout, "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD");
    if (head.ok() && head.out().startsWith("origin/")) return head.out().substring("origin/".length());
    if (hasRemote(checkout)) {
      GitRunner.Result ls = git.run(checkout, Duration.ofSeconds(20), "ls-remote", "--symref", "origin", "HEAD");
      if (ls.ok()) {
        for (String line : ls.out().split("\n")) {
          // "ref: refs/heads/main<TAB>HEAD"
          if (line.startsWith("ref: refs/heads/")) {
            String ref = line.substring("ref: ".length()).split("\\s+")[0];
            return ref.substring("refs/heads/".length());
          }
        }
      }
    }
    for (String candidate : List.of("main", "master", "develop")) {
      if (refExists(checkout, "refs/remotes/origin/" + candidate) || refExists(checkout, "refs/heads/" + candidate)) {
        return candidate;
      }
    }
    GitRunner.Result cur = git.run(checkout, "branch", "--show-current");
    return cur.ok() && !cur.out().isEmpty() ? cur.out() : "main";
  }

  // --------------------------------------------------------------- tickets

  /**
   * Creates {@code <workspace>/<ticketName>/} with a worktree per included repo, copies the configured
   * files from main, and prepares .idea. Repos that fail are reported; the ticket is kept if at least
   * one worktree was created.
   */
  public Ticket createTicket(Workspace ws, String ticketName, String branch, List<RepoChoice> choices, Log log)
      throws IOException, GitException {
    if (!Workspace.isValidName(ticketName) || ticketName.equalsIgnoreCase(Workspace.MAIN)) {
      throw new IOException("Invalid ticket name: " + ticketName);
    }
    if (branch == null || branch.isBlank()) throw new IOException("Branch name is empty");
    Ticket ticket = ws.ticket(ticketName);
    Path dir = ticket.getDir();
    if (Files.exists(dir) || existsIgnoreCase(ws.getRoot(), ticketName)) {
      throw new IOException(ws.getRoot().relativize(dir) + " already exists");
    }
    if (choices.stream().noneMatch(RepoChoice::include)) throw new IOException("Pick at least one repo");

    Files.createDirectories(dir);
    List<String> failures = new ArrayList<>();
    int created = 0;
    for (RepoChoice c : choices) {
      if (!c.include()) continue;
      try {
        addWorktree(ws, ticket, c.repo(), branch.trim(), c.baseBranch(), log);
        created++;
      } catch (GitException | IOException e) {
        failures.add(c.repo() + ": " + e.getMessage());
        log.warn(c.repo() + ": " + e.getMessage());
      }
    }
    if (created == 0) {
      FileCloner.deleteQuietly(dir);
      throw new GitException("No worktree could be created.\n" + String.join("\n", failures));
    }
    IdeaFiles.prepare(ticket);
    return ticket;
  }

  /** Adds a worktree for {@code repo} to an existing ticket (also used to extend a ticket later). */
  public void addWorktree(Workspace ws, Ticket ticket, String repo, String branch, String baseBranch, Log log)
      throws GitException, IOException {
    Path mainRepo = ws.mainDir().resolve(repo);
    if (!Ticket.isGitDir(mainRepo)) throw new GitException("main/" + repo + " is not a git checkout");
    Path target = ticket.getDir().resolve(repo);
    if (Files.exists(target)) throw new GitException(ws.getRoot().relativize(target) + " already exists");

    String base = baseBranch == null || baseBranch.isBlank() ? configuredBase(ws, repo, mainRepo) : baseBranch.trim();
    checkBranchName(mainRepo, branch);
    checkBranchName(mainRepo, base);

    log.step(repo + ": fetching origin/" + base);
    if (hasRemote(mainRepo)) {
      GitRunner.Result fetch = git.run(mainRepo, FETCH_TIMEOUT, "fetch", "origin", base);
      if (!fetch.ok()) log.warn(repo + ": fetch failed, using the local state (" + fetch.message() + ")");
      // the ticket branch may already exist on the remote (pushed by a colleague); fails quietly when it doesn't
      git.run(mainRepo, FETCH_TIMEOUT, "fetch", "origin", "+refs/heads/" + branch + ":refs/remotes/origin/" + branch);
    }

    log.step(repo + ": creating worktree on " + branch);
    GitRunner.Result add;
    if (refExists(mainRepo, "refs/heads/" + branch)) {
      add = git.run(mainRepo, CLONE_TIMEOUT, "worktree", "add", target.toString(), branch);
    } else if (refExists(mainRepo, "refs/remotes/origin/" + branch)) {
      add = git.run(mainRepo, CLONE_TIMEOUT, "worktree", "add", "--track", "-b", branch, target.toString(),
          "origin/" + branch);
    } else {
      String start;
      if (refExists(mainRepo, "refs/remotes/origin/" + base)) start = "origin/" + base;
      else if (refExists(mainRepo, "refs/heads/" + base)) start = base;
      else {
        log.warn(repo + ": base branch '" + base + "' not found, branching from main/" + repo + "'s HEAD");
        start = "HEAD";
      }
      add = git.run(mainRepo, CLONE_TIMEOUT, "worktree", "add", "--no-track", "-b", branch, target.toString(), start);
    }
    if (!add.ok()) {
      // a half-made worktree (timeout, killed checkout) would block a retry with "already registered"
      git.run(mainRepo, "worktree", "prune");
      add.orThrow("git worktree add failed");
    }

    WorkspaceConfig.RepoConfig rc = ws.getConfig().getRepos().get(repo);
    List<String> copy = rc != null ? rc.getCopy() : WorkspaceConfig.DEFAULT_COPY;
    for (String entry : copy) {
      if (entry.contains("..") || Path.of(entry).isAbsolute()) continue;
      Path src = mainRepo.resolve(entry);
      if (!Files.exists(src)) continue;
      log.step(repo + ": copying " + entry);
      try {
        FileCloner.copy(src, target.resolve(entry));
      } catch (IOException e) {
        log.warn(repo + ": could not copy " + entry + " (" + e.getMessage() + ")");
      }
    }
  }

  /** Rejects names git wouldn't accept as a branch, including ones starting with '-' (read as an option). */
  private void checkBranchName(Path repo, String name) throws GitException {
    if (name.startsWith("-") || !git.run(repo, "check-ref-format", "--branch", name).ok()) {
      throw new GitException("Invalid branch name: " + name);
    }
  }

  private String configuredBase(Workspace ws, String repo, Path mainRepo) {
    WorkspaceConfig.RepoConfig rc = ws.getConfig().getRepos().get(repo);
    if (rc != null && rc.getBaseBranch() != null) return rc.getBaseBranch();
    return detectDefaultBranch(mainRepo);
  }

  private boolean hasRemote(Path repo) {
    GitRunner.Result r = git.run(repo, "remote");
    return r.ok() && r.out().lines().anyMatch("origin"::equals);
  }

  private boolean refExists(Path repo, String ref) {
    return git.run(repo, "show-ref", "--verify", "--quiet", ref).ok();
  }

  // ---------------------------------------------------------------- finish

  /** A reason not to finish a ticket yet. */
  public record Problem(String repo, String text) {}

  /** Uncommitted changes and commits that exist on no remote, per worktree. */
  public List<Problem> checkBeforeFinish(Ticket ticket) {
    List<Problem> problems = new ArrayList<>();
    List<TicketRepo> worktrees = ticket.repos().stream().filter(r -> r.kind() == TicketRepo.Kind.WORKTREE).toList();
    if (worktrees.isEmpty()) problems.add(new Problem("folder", "has no worktrees"));
    for (TicketRepo r : worktrees) {
      RepoStatus st = RepoStatus.read(git, r.dir());
      if (st.error() != null) {
        problems.add(new Problem(r.name(), "git error: " + st.error()));
        continue;
      }
      if (st.changed() > 0) problems.add(new Problem(r.name(), st.changed() + " uncommitted change(s)"));
      if (st.unpushed() > 0) problems.add(new Problem(r.name(), st.unpushed() + " commit(s) not pushed to any remote"));
    }
    List<String> other = leftovers(ticket, worktrees.stream().map(TicketRepo::name).toList());
    if (!other.isEmpty()) {
      problems.add(new Problem("folder", "also contains " + String.join(", ", other) + " (the folder will be kept)"));
    }
    return problems;
  }

  /** Entries of the ticket folder that are neither worktrees nor IDE metadata. */
  static List<String> leftovers(Ticket ticket, List<String> worktreeNames) {
    List<String> out = new ArrayList<>();
    if (!Files.isDirectory(ticket.getDir())) return out;
    try (Stream<Path> s = Files.list(ticket.getDir())) {
      s.map(p -> p.getFileName().toString())
          .filter(n -> !n.equals(".idea") && !n.equals(".DS_Store") && !worktreeNames.contains(n))
          .sorted()
          .forEach(out::add);
    } catch (IOException e) {
      out.add("(unreadable)");
    }
    return out;
  }

  /** True for folders that must never be finished: the workspace root, main/, anything outside. */
  static boolean isProtected(Ticket ticket) {
    Workspace ws = ticket.getWorkspace();
    Path dir = ticket.getDir().toAbsolutePath().normalize();
    if (ticket.isMain() || !dir.startsWith(ws.getRoot()) || dir.equals(ws.getRoot())) return true;
    if (!dir.getParent().equals(ws.getRoot())) return true;
    try {
      if (Files.exists(dir) && Files.exists(ws.mainDir()) && Files.isSameFile(dir, ws.mainDir())) return true;
    } catch (IOException e) {
      return true;
    }
    return false;
  }

  /** Result of finishing a ticket. */
  public record FinishResult(boolean folderRemoved, List<String> messages) {}

  /**
   * Removes the ticket's worktrees ({@code git worktree remove}) and then its folder.
   * With {@code force}, uncommitted work in the worktrees is discarded.
   * With {@code deleteMergedBranches}, {@code git branch -d} is tried (only succeeds for merged branches).
   */
  public FinishResult finishTicket(Ticket ticket, boolean force, boolean deleteMergedBranches, Log log)
      throws IOException {
    Workspace ws = ticket.getWorkspace();
    Path dir = ticket.getDir().toAbsolutePath().normalize();
    if (isProtected(ticket)) throw new IOException("The main checkout can't be finished");
    List<String> messages = new ArrayList<>();
    List<TicketRepo> worktrees = ticket.repos().stream().filter(r -> r.kind() == TicketRepo.Kind.WORKTREE).toList();
    List<RepoStatus> statuses = worktrees.stream().map(r -> RepoStatus.read(git, r.dir())).toList();
    if (!force) {
      // all or nothing: don't remove the clean worktrees and leave a half-finished ticket behind
      for (int i = 0; i < worktrees.size(); i++) {
        if (statuses.get(i).changed() > 0) {
          messages.add(worktrees.get(i).name() + ": " + statuses.get(i).changed() + " uncommitted change(s)");
        }
      }
      if (!messages.isEmpty()) return new FinishResult(false, messages);
    }
    boolean allRemoved = true;
    for (int i = 0; i < worktrees.size(); i++) {
      TicketRepo r = worktrees.get(i);
      Path mainRepo = ws.mainDir().resolve(r.name());
      String branch = statuses.get(i).branch();
      log.step(r.name() + ": removing worktree");
      List<String> args = new ArrayList<>(List.of("worktree", "remove"));
      if (force) args.add("--force");
      args.add(r.dir().toString());
      GitRunner.Result rm = git.run(Ticket.isGitDir(mainRepo) ? mainRepo : r.dir(), args.toArray(String[]::new));
      if (!rm.ok()) {
        allRemoved = false;
        messages.add(r.name() + ": worktree not removed (" + rm.message() + ")");
        continue;
      }
      messages.add(r.name() + ": worktree removed");
      if (deleteMergedBranches && branch != null && !branch.startsWith("(") && Ticket.isGitDir(mainRepo)) {
        GitRunner.Result del = git.run(mainRepo, "branch", "-d", branch);
        messages.add(r.name() + (del.ok() ? ": deleted branch " + branch
            : ": kept branch " + branch + " (not fully merged)"));
      }
    }
    if (!allRemoved) return new FinishResult(false, messages);

    for (String repo : ws.getConfig().getRepos().keySet()) {
      Path mainRepo = ws.mainDir().resolve(repo);
      if (Ticket.isGitDir(mainRepo)) git.run(mainRepo, "worktree", "prune");
    }
    // only delete when nothing but IDE metadata is left: never someone's notes or a non-ticket folder
    List<String> other = leftovers(ticket, List.of());
    if (!other.isEmpty()) {
      messages.add("Kept " + ws.getRoot().relativize(dir) + "/ because it still contains " + String.join(", ", other));
      return new FinishResult(false, messages);
    }
    log.step("Removing " + ws.getRoot().relativize(dir));
    FileCloner.deleteRecursively(dir);
    return new FinishResult(true, messages);
  }

  private static boolean existsIgnoreCase(Path parent, String name) {
    if (!Files.isDirectory(parent)) return false;
    try (Stream<Path> s = Files.list(parent)) {
      return s.anyMatch(p -> p.getFileName().toString().equalsIgnoreCase(name));
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean isEmptyDir(Path p) throws IOException {
    if (!Files.isDirectory(p)) return false;
    try (Stream<Path> s = Files.list(p)) {
      return s.findAny().isEmpty();
    }
  }
}
