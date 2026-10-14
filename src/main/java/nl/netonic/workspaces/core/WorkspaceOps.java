package nl.netonic.workspaces.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
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

  /**
   * What to do for one repo when creating a ticket. {@code baseBranch} is a full ref (refs/heads/develop,
   * refs/remotes/origin/develop), a plain name (local branch first, then origin's), or empty for the default.
   */
  public record RepoChoice(String repo, boolean include, String baseBranch) {}

  /** Branches of a repo as full refs, sorted; remote ones without {@code <remote>/HEAD}. */
  public record Branches(List<String> local, List<String> remote) {}

  /** Preferences from the IDE: links next to the workspace on/off, files copied into new worktrees. */
  public record Options(boolean links, List<String> defaultCopy) {
    public static final Options DEFAULT = new Options(true, WorkspaceConfig.DEFAULT_COPY);
  }

  private final GitRunner git;
  private final Options options;

  public WorkspaceOps(GitRunner git) {
    this(git, Options.DEFAULT);
  }

  public WorkspaceOps(GitRunner git, Options options) {
    this.git = git;
    this.options = options;
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

  /**
   * The workspace folder may be a checkout that is about to be adopted: workspace "netcaly" made from
   * ~/PhpstormProjects/netcaly (on macOS also "Netcaly"). Renames that checkout aside so {@link #createWorkspace}
   * can make the folder, and returns where it is now; any other folder is returned as-is.
   */
  public Path moveAsideForWorkspace(Path parent, String name, Path checkout, Log log) throws IOException {
    Path source = checkout.toAbsolutePath().normalize();
    if (!isSameFolder(parent.resolve(name), source)) return checkout;
    checkAdoptable(source); // fail before moving anything
    Path aside = source.resolveSibling(source.getFileName() + ".moving");
    for (int i = 2; Files.exists(aside, LinkOption.NOFOLLOW_LINKS); i++) {
      aside = source.resolveSibling(source.getFileName() + ".moving-" + i);
    }
    log.step("Moving " + source.getFileName() + " aside to make room for the workspace");
    Files.move(source, aside);
    return aside;
  }

  /** Both are the same real folder (also when only the case differs on macOS), neither of them a link. */
  public static boolean isSameFolder(Path a, Path b) {
    if (Files.isSymbolicLink(a) || Files.isSymbolicLink(b) || !Files.isDirectory(a) || !Files.isDirectory(b)) return false;
    try {
      return Files.isSameFile(a, b);
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Clones {@code url} into main/&lt;repoName&gt; and registers it. With {@code repoName} null it becomes the
   * workspace's only repo, cloned into main/ itself.
   */
  public void addRepoByClone(Workspace ws, String repoName, String url, Log log) throws IOException, GitException {
    Path target = newRepoTarget(ws, repoName);
    String key = repoName == null ? ws.getName() : repoName;
    log.step("Cloning " + url);
    Files.createDirectories(target.getParent());
    git.run(target.getParent(), CLONE_TIMEOUT, "clone", "--", url, target.getFileName().toString())
        .orThrow("git clone failed");
    registerRepo(ws, key, target, url, null);
    if (options.links()) Links.create(ws.ticket(Workspace.MAIN), key, log);
  }

  /**
   * Moves an existing checkout into main/&lt;repoName&gt; (main/ itself with {@code repoName} null, see
   * {@link #addRepoByClone}) and registers it. Runs {@code git worktree repair} so worktrees made earlier keep working.
   */
  public void adoptRepo(Workspace ws, String repoName, Path existing, Log log) throws IOException, GitException {
    adoptRepo(ws, repoName, existing, existing, log);
  }

  /**
   * As {@link #adoptRepo(Workspace, String, Path, Log)}, for a checkout that was first moved aside
   * ({@link #moveAsideForWorkspace}): {@code cameFrom} is its original path, saved for Undo Workspace.
   */
  public void adoptRepo(Workspace ws, String repoName, Path existing, Path cameFrom, Log log)
      throws IOException, GitException {
    Path source = existing.toAbsolutePath().normalize();
    checkAdoptable(source);
    Path target = newRepoTarget(ws, repoName);
    String key = repoName == null ? ws.getName() : repoName;
    if (target.startsWith(source)) throw new IOException("Can't move a folder into itself");
    log.step("Moving " + source.getFileName() + " to " + ws.getRoot().relativize(target));
    Files.createDirectories(target.getParent());
    if (repoName == null && Files.isDirectory(target)) Files.delete(target); // the empty main/ (checked above)
    Files.move(source, target);
    GitRunner.Result repair = git.run(target, "worktree", "repair");
    if (!repair.ok()) log.warn("git worktree repair: " + repair.message());
    GitRunner.Result origin = git.run(target, "remote", "get-url", "origin");
    registerRepo(ws, key, target, origin.ok() ? origin.out() : null, cameFrom.toAbsolutePath().normalize());
    // usually lands on the old path (klant-x-backend), so tools that knew it keep working
    if (options.links()) Links.create(ws.ticket(Workspace.MAIN), key, log);
  }

  private static void checkAdoptable(Path source) throws IOException {
    if (Files.isSymbolicLink(source)) throw new IOException(source + " is a link; adopt the folder it points to");
    if (!Ticket.isGitDir(source)) throw new IOException(source + " is not a git checkout");
    if (Files.isRegularFile(source.resolve(".git"))) {
      throw new IOException(source + " is itself a worktree; adopt the main clone instead");
    }
    Workspace owner = Workspace.findContaining(source);
    if (owner != null) {
      // moving it would break that workspace's worktrees (and its main/ must never move)
      throw new IOException(source + " belongs to workspace " + owner.getName() + "; clone it by URL instead");
    }
  }

  /** main/&lt;repoName&gt;, or main/ itself for a workspace's only repo ({@code repoName} null), which must be empty. */
  private Path newRepoTarget(Workspace ws, String repoName) throws IOException {
    if (repoName == null) {
      if (Files.exists(ws.mainDir()) && !isEmptyDir(ws.mainDir())) {
        throw new IOException("main/ is not empty; give the repo a name to put it in main/<name>");
      }
      return ws.mainDir();
    }
    if (ws.isSingleRepo()) {
      throw new IOException(ws.getName() + " has its one repo in main/ itself; other repos can't be added to it");
    }
    if (!Workspace.isValidName(repoName)) throw new IOException("Invalid repo name: " + repoName);
    Path target = ws.mainDir().resolve(repoName);
    if (Files.exists(target)) throw new IOException(ws.getRoot().relativize(target) + " already exists");
    return target;
  }

  private static final Object CONFIG_LOCK = new Object();

  /** Re-reads .workspace.json, applies {@code change}, writes it, and applies it in memory too. */
  private static void updateConfig(Workspace ws, Consumer<WorkspaceConfig> change) throws IOException {
    synchronized (CONFIG_LOCK) {
      WorkspaceConfig onDisk = Files.isRegularFile(ws.configFile()) ? WorkspaceConfig.load(ws.configFile()) : ws.getConfig();
      change.accept(onDisk);
      onDisk.save(ws.configFile());
      if (onDisk != ws.getConfig()) change.accept(ws.getConfig());
    }
  }

  /** Re-reads .workspace.json right before writing, so edits made meanwhile are kept. */
  private void registerRepo(Workspace ws, String repoName, Path checkout, String url, Path adoptedFrom)
      throws IOException {
    String base = detectDefaultBranch(checkout);
    synchronized (CONFIG_LOCK) {
      WorkspaceConfig onDisk = Files.isRegularFile(ws.configFile()) ? WorkspaceConfig.load(ws.configFile()) : ws.getConfig();
      WorkspaceConfig.RepoConfig rc = onDisk.getRepos().computeIfAbsent(repoName, k -> {
        WorkspaceConfig.RepoConfig fresh = new WorkspaceConfig.RepoConfig();
        fresh.setCopy(options.defaultCopy());
        return fresh;
      });
      rc.setUrl(url);
      if (adoptedFrom != null) rc.setAdoptedFrom(adoptedFrom.toString());
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
    try {
      updateConfig(ws, cfg -> cfg.setTicketBranch(ticketName, branch.trim()));
    } catch (IOException e) {
      log.warn("Could not save the branch in " + WorkspaceConfig.FILE_NAME + ": " + e.getMessage());
    }
    return ticket;
  }

  /** Adds a worktree for {@code repo} to an existing ticket (also used to extend a ticket later). */
  public void addWorktree(Workspace ws, Ticket ticket, String repo, String branch, String baseBranch, Log log)
      throws GitException, IOException {
    Path mainRepo = ws.mainRepoDir(repo);
    if (!Ticket.isGitDir(mainRepo)) throw new GitException(ws.getRoot().relativize(mainRepo) + " is not a git checkout");
    Path target = ws.repoDir(ticket.getName(), repo);
    // a single-repo ticket is the worktree itself: its folder exists, but must still be empty
    boolean emptyTicketDir = target.equals(ticket.getDir()) && isEmptyDir(target);
    if (Files.exists(target) && !emptyTicketDir) {
      throw new GitException(ws.getRoot().relativize(target) + " already exists");
    }

    String base = baseBranch == null || baseBranch.isBlank() ? configuredBase(ws, repo, mainRepo) : baseBranch.trim();
    String baseRemote = null;
    boolean explicit = true;
    if (base.startsWith("refs/heads/")) {
      base = base.substring("refs/heads/".length());
    } else if (base.startsWith("refs/remotes/")) {
      String rest = base.substring("refs/remotes/".length());
      int slash = rest.indexOf('/');
      baseRemote = slash > 0 ? rest.substring(0, slash) : "";
      base = slash > 0 ? rest.substring(slash + 1) : "";
      if (!remotes(mainRepo).contains(baseRemote)) throw new GitException("Unknown remote in " + baseBranch);
    } else {
      explicit = false;
    }
    checkBranchName(mainRepo, branch);
    checkBranchName(mainRepo, base);

    // always fetch the base, also for a local pick (that one is still used as it is)
    String fetchFrom = baseRemote != null ? baseRemote : hasRemote(mainRepo) ? "origin" : null;
    if (fetchFrom != null) {
      log.step(repo + ": fetching " + fetchFrom + "/" + base);
      GitRunner.Result fetch = git.run(mainRepo, FETCH_TIMEOUT, "fetch", fetchFrom, base);
      if (!fetch.ok() && (baseRemote != null || !refExists(mainRepo, "refs/heads/" + base))) {
        log.warn(repo + ": fetch failed, using the local state (" + fetch.message() + ")");
      }
    }
    if (hasRemote(mainRepo)) {
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
      String start = startPoint(mainRepo, base, baseRemote, explicit);
      if (start == null) {
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
    if (options.links()) Links.create(ticket, repo, log);
  }

  /** Rejects names git wouldn't accept as a branch, including ones starting with '-' (read as an option). */
  private void checkBranchName(Path repo, String name) throws GitException {
    if (name.startsWith("-") || !git.run(repo, "check-ref-format", "--branch", name).ok()) {
      throw new GitException("Invalid branch name: " + name);
    }
  }

  /**
   * The ref to branch from. A picked branch must exist; a plain name is the local branch, else origin's,
   * else null (the caller falls back to HEAD).
   */
  private String startPoint(Path repo, String base, String remote, boolean explicit) throws GitException {
    if (remote != null) {
      if (refExists(repo, "refs/remotes/" + remote + "/" + base)) return "refs/remotes/" + remote + "/" + base;
      throw new GitException("Branch " + remote + "/" + base + " not found");
    }
    if (refExists(repo, "refs/heads/" + base)) return "refs/heads/" + base;
    if (explicit) throw new GitException("Branch " + base + " not found");
    if (refExists(repo, "refs/remotes/origin/" + base)) return "refs/remotes/origin/" + base;
    return null;
  }

  /** Local and remote-tracking branches, the same set PhpStorm's Git branches popup shows. */
  public Branches listBranches(Path repo) throws GitException {
    GitRunner.Result r = git.run(repo, "for-each-ref", "--format=%(refname)", "refs/heads", "refs/remotes");
    r.orThrow("git for-each-ref failed");
    List<String> local = new ArrayList<>();
    List<String> remote = new ArrayList<>();
    for (String ref : r.out().split("\n")) {
      if (ref.startsWith("refs/heads/")) local.add(ref);
      else if (ref.startsWith("refs/remotes/") && !ref.endsWith("/HEAD")) remote.add(ref);
    }
    local.sort(String.CASE_INSENSITIVE_ORDER);
    remote.sort(String.CASE_INSENSITIVE_ORDER);
    return new Branches(local, remote);
  }

  private List<String> remotes(Path repo) {
    GitRunner.Result r = git.run(repo, "remote");
    return r.ok() ? r.out().lines().toList() : List.of();
  }

  private String configuredBase(Workspace ws, String repo, Path mainRepo) {
    WorkspaceConfig.RepoConfig rc = ws.getConfig().getRepos().get(repo);
    if (rc != null && rc.getBaseBranch() != null) return rc.getBaseBranch();
    return detectDefaultBranch(mainRepo);
  }

  private boolean hasRemote(Path repo) {
    return remotes(repo).contains("origin");
  }

  private boolean refExists(Path repo, String ref) {
    return git.run(repo, "show-ref", "--verify", "--quiet", ref).ok();
  }

  /** Link names created and removed by {@link #syncLinks}. */
  public record LinkChanges(List<String> created, List<String> removed) {}

  /**
   * Creates the missing links of main and every ticket, and removes links whose ticket or repo is gone.
   * Run on every scan, so links follow the folders whatever made or removed them. With links switched off,
   * removes all of this workspace's links instead.
   */
  public LinkChanges syncLinks(Workspace ws, Log log) {
    if (!options.links()) return new LinkChanges(List.of(), Links.removeAll(ws, log));
    List<String> created = new ArrayList<>();
    for (Ticket t : ws.tickets()) created.addAll(Links.sync(t, log));
    return new LinkChanges(created, Links.tidy(ws, log));
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
    // a single-repo ticket folder is the worktree: everything in it is the repo's
    List<String> other = ticket.getWorkspace().isSingleRepo() ? List.of()
        : leftovers(ticket, worktrees.stream().map(TicketRepo::name).toList());
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
      Path mainRepo = ws.mainRepoDir(r.name());
      String branch = statuses.get(i).branch();
      log.step(r.name() + ": removing worktree");
      // untracked IDE metadata isn't work (RepoStatus skips it too), but would make git refuse the removal
      git.run(r.dir(), "clean", "-f", "-d", "-q", "--", ".idea", ".DS_Store", "*/.DS_Store");
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
      try {
        String link = Links.remove(ticket, r.name());
        if (link != null) messages.add(r.name() + ": removed link " + link);
      } catch (IOException e) {
        messages.add(r.name() + ": link not removed (" + e.getMessage() + ")");
      }
      if (deleteMergedBranches && branch != null && !branch.startsWith("(") && Ticket.isGitDir(mainRepo)) {
        GitRunner.Result del = git.run(mainRepo, "branch", "-d", branch);
        messages.add(r.name() + (del.ok() ? ": deleted branch " + branch
            : ": kept branch " + branch + " (not fully merged)"));
      }
    }
    if (!allRemoved) return new FinishResult(false, messages);

    for (String repo : ws.getConfig().getRepos().keySet()) {
      Path mainRepo = ws.mainRepoDir(repo);
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
    try {
      updateConfig(ws, cfg -> cfg.removeTicket(ticket.getName()));
    } catch (IOException e) {
      messages.add("Could not update " + WorkspaceConfig.FILE_NAME + ": " + e.getMessage());
    }
    return new FinishResult(true, messages);
  }

  // ------------------------------------------------------------------ undo

  /** Result of undoing a workspace: where each repo is now, and whether the workspace folder is gone. */
  public record UndoResult(Map<String, Path> repos, boolean folderRemoved, List<String> messages) {}

  /** What stops Undo Workspace: uncommitted work (or a git error) in a ticket's worktree. Unpushed commits don't. */
  public List<Problem> checkBeforeUndo(Workspace ws) {
    List<Problem> problems = new ArrayList<>();
    for (Ticket t : ws.tickets()) {
      if (t.isMain()) continue;
      for (TicketRepo r : t.repos()) {
        if (r.kind() != TicketRepo.Kind.WORKTREE) continue;
        String where = ws.isSingleRepo() ? t.getName() : t.getName() + "/" + r.name();
        RepoStatus st = RepoStatus.read(git, r.dir());
        if (st.error() != null) problems.add(new Problem(where, "git error: " + st.error()));
        else if (st.changed() > 0) problems.add(new Problem(where, st.changed() + " uncommitted change(s)"));
      }
    }
    return problems;
  }

  /**
   * Reverses New Workspace. Removes every ticket's worktrees (their branches and commits stay in the repo), moves
   * each repo out of main/ to where it was adopted from, else next to the workspace as &lt;workspace&gt;-&lt;repo&gt;
   * (a single-repo workspace's repo takes the workspace's own path), removes the links, and deletes the workspace
   * folder unless something else is left in it. Never deletes a repo; refuses while a ticket has uncommitted work.
   */
  public UndoResult undoWorkspace(Workspace workspace, Log log) throws IOException {
    Workspace ws = Workspace.load(workspace.getRoot());
    List<Problem> problems = checkBeforeUndo(ws);
    if (!problems.isEmpty()) {
      throw new IOException("Commit or discard these changes first:\n"
          + String.join("\n", problems.stream().map(p -> p.repo() + ": " + p.text()).toList()));
    }
    Path root = ws.getRoot();
    Path parent = root.getParent();
    if (parent == null) throw new IOException("The workspace has no parent folder");
    List<String> messages = new ArrayList<>();

    // tickets first: main/'s repos only move once no worktree points at them
    for (Ticket t : ws.tickets()) {
      if (t.isMain()) continue;
      FinishResult fr = finishTicket(t, false, false, log);
      fr.messages().forEach(m -> messages.add(t.getName() + ": " + m));
      if (t.repos().stream().anyMatch(r -> r.kind() == TicketRepo.Kind.WORKTREE)) {
        throw new IOException(t.getName() + ": not every worktree could be removed, nothing was moved out of main/\n"
            + String.join("\n", fr.messages()));
      }
    }
    Links.removeAll(ws, log).forEach(l -> messages.add("Removed link " + l));

    boolean single = ws.isSingleRepo();
    Map<String, Path> now = new LinkedHashMap<>();
    Map<String, Path> later = new LinkedHashMap<>(); // destinations inside the workspace folder: once it's gone
    for (TicketRepo r : ws.ticket(Workspace.MAIN).repos()) {
      if (r.kind() != TicketRepo.Kind.CLONE) continue;
      Path dest = restorePath(ws, r.name(), single);
      boolean inside = dest.startsWith(root);
      Path to = free(inside ? parent.resolve(root.getFileName() + ".undo-" + r.name()) : dest);
      log.step("Moving " + root.relativize(r.dir()) + " to " + to);
      Files.move(r.dir(), to);
      git.run(to, "worktree", "prune");
      now.put(r.name(), to);
      if (inside) later.put(r.name(), dest);
    }

    // the workspace folder: its config and main/'s IDE files go, anything else is kept
    Files.deleteIfExists(ws.configFile());
    if (Set.of(".idea", ".DS_Store").containsAll(entries(ws.mainDir()))) FileCloner.deleteRecursively(ws.mainDir());
    List<String> left = entries(root).stream().filter(n -> !n.equals(".DS_Store")).toList();
    boolean removed = left.isEmpty();
    if (removed) {
      log.step("Removing " + root);
      FileCloner.deleteRecursively(root);
    } else {
      messages.add("Kept " + root + " because it still contains " + String.join(", ", left));
    }

    for (Map.Entry<String, Path> e : later.entrySet()) {
      Path dest = e.getValue();
      // taken (the workspace folder was kept): klant-x-backend, or klant-x-2 for a single repo
      Path to = Files.exists(dest, LinkOption.NOFOLLOW_LINKS) ? free(fallbackPath(ws, e.getKey(), single)) : dest;
      Files.move(now.get(e.getKey()), to);
      now.put(e.getKey(), to);
    }
    now.forEach((repo, p) -> messages.add(repo + " is now " + p));
    return new UndoResult(now, removed, messages);
  }

  /** Where Undo Workspace means to put each repo of main/ (a taken name then gets -2, -3, ...). */
  public static Map<String, Path> undoDestinations(Workspace ws) {
    boolean single = ws.isSingleRepo();
    Map<String, Path> out = new LinkedHashMap<>();
    for (TicketRepo r : ws.ticket(Workspace.MAIN).repos()) {
      if (r.kind() == TicketRepo.Kind.CLONE) out.put(r.name(), restorePath(ws, r.name(), single));
    }
    return out;
  }

  /** Where a repo goes back to: the folder it was adopted from (if its parent still exists), else the fallback. */
  private static Path restorePath(Workspace ws, String repo, boolean single) {
    WorkspaceConfig.RepoConfig rc = ws.getConfig().getRepos().get(repo);
    if (rc != null && rc.getAdoptedFrom() != null) {
      try {
        Path p = Path.of(rc.getAdoptedFrom()).toAbsolutePath().normalize();
        if (p.getParent() != null && Files.isDirectory(p.getParent()) && !p.startsWith(ws.mainDir())) return p;
      } catch (RuntimeException ignored) {
        // not a usable path: fall back
      }
    }
    return fallbackPath(ws, repo, single);
  }

  /** klant-x-backend next to the workspace; a single-repo workspace's repo takes the workspace's own path. */
  private static Path fallbackPath(Workspace ws, String repo, boolean single) {
    return ws.getRoot().resolveSibling(single ? ws.getRoot().getFileName().toString() : ws.getName() + "-" + repo);
  }

  /** {@code p}, or p-2, p-3, ... when something (even a dangling link) is there already. */
  private static Path free(Path p) {
    Path candidate = p;
    for (int i = 2; Files.exists(candidate, LinkOption.NOFOLLOW_LINKS); i++) {
      candidate = p.resolveSibling(p.getFileName() + "-" + i);
    }
    return candidate;
  }

  private static List<String> entries(Path dir) throws IOException {
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
    try (Stream<Path> s = Files.list(dir)) {
      return s.map(p -> p.getFileName().toString()).sorted().toList();
    }
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
