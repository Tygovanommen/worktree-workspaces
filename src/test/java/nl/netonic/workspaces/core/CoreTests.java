package nl.netonic.workspaces.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free tests for the core, run against real git repos in a temp folder.
 * Run: java -cp out/classes:out/test nl.netonic.workspaces.core.CoreTests
 */
public final class CoreTests {
  private static int passed;
  private static final List<String> failed = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    test("json round trip", CoreTests::jsonRoundTrip);
    test("config defaults + load/save", CoreTests::configLoadSave);
    test("porcelain v2 parsing", CoreTests::porcelainParse);
    test("full flow: workspace, adopt, clone, tickets, finish", CoreTests::fullFlow);
    test("invalid names and main protection", CoreTests::guards);

    System.out.println();
    System.out.println(passed + " passed, " + failed.size() + " failed");
    failed.forEach(f -> System.out.println("  FAILED: " + f));
    if (!failed.isEmpty()) System.exit(1);
  }

  interface Body { void run() throws Exception; }

  private static void test(String name, Body body) {
    try {
      body.run();
      passed++;
      System.out.println("ok    " + name);
    } catch (Throwable t) {
      failed.add(name + ": " + t);
      System.out.println("FAIL  " + name);
      t.printStackTrace(System.out);
    }
  }

  private static void check(boolean cond, String what) {
    if (!cond) throw new AssertionError(what);
  }

  private static void eq(Object expected, Object actual, String what) {
    if (expected == null ? actual != null : !expected.equals(actual)) {
      throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }
  }

  // ---------------------------------------------------------------- tests

  static void jsonRoundTrip() {
    String src = "{\"a\": \"x \\\"q\\\" \\u00e9\", \"b\": [1, 2.5, true, null], \"c\": {\"d\": []}}";
    Object parsed = Json.parse(src);
    Object again = Json.parse(Json.write(parsed));
    eq(parsed, again, "round trip");
    eq("x \"q\" \u00e9", ((Map<?, ?>) parsed).get("a"), "escapes");
    try {
      Json.parse("{\"a\": }");
      throw new AssertionError("expected parse error");
    } catch (IllegalArgumentException expected) {
      // ok
    }
  }

  static void configLoadSave() throws IOException {
    Path dir = Files.createTempDirectory("cfg");
    Path f = dir.resolve(WorkspaceConfig.FILE_NAME);
    Files.writeString(f, """
        { "name": "klant-x", "repos": { "backend": { "baseBranch": "develop" }, "frontend": {} } }
        """);
    WorkspaceConfig c = WorkspaceConfig.load(f);
    eq("klant-x", c.getName(), "name");
    eq("feature/LOGIN-42", c.branchFor("LOGIN-42"), "default branch pattern");
    eq(List.of("backend", "frontend"), new ArrayList<>(c.getRepos().keySet()), "repo order");
    eq("develop", c.getRepos().get("backend").getBaseBranch(), "base");
    eq(WorkspaceConfig.DEFAULT_COPY, c.getRepos().get("frontend").getCopy(), "default copy list");
    c.setBranchPattern("bugfix/{ticket}");
    c.save(f);
    WorkspaceConfig c2 = WorkspaceConfig.load(f);
    eq("bugfix/X-1", c2.branchFor("X-1"), "pattern saved");
    eq(WorkspaceConfig.DEFAULT_COPY, c2.getRepos().get("backend").getCopy(), "copy saved");
  }

  static void porcelainParse() {
    RepoStatus s = RepoStatus.parse("""
        # branch.oid abc
        # branch.head feature/X
        # branch.upstream origin/feature/X
        # branch.ab +2 -1
        1 .M N... 100644 100644 100644 a b file.txt
        ? new.txt
        """);
    eq("feature/X", s.branch(), "branch");
    eq("origin/feature/X", s.upstream(), "upstream");
    eq(2, s.ahead(), "ahead");
    eq(1, s.behind(), "behind");
    eq(2, s.changed(), "changed");
  }

  static void fullFlow() throws Exception {
    Path tmp = Files.createTempDirectory("wsflow");
    GitRunner git = GitRunner.fromSystem();
    WorkspaceOps ops = new WorkspaceOps(git);

    // two "remote" repos, like GitHub
    Path remoteBackend = bareRepoWithCommit(git, tmp, "remote-backend", "develop");
    Path remoteFrontend = bareRepoWithCommit(git, tmp, "remote-frontend", "main");

    // an existing checkout of the backend, as in ~/PhpstormProjects/klant-x-backend
    Path existing = tmp.resolve("klant-x-backend");
    git.run(tmp, "clone", remoteBackend.toString(), existing.toString()).orThrow("clone");
    Files.writeString(existing.resolve(".env"), "APP_ENV=local\n");
    Files.createDirectories(existing.resolve("vendor/acme"));
    Files.writeString(existing.resolve("vendor/acme/lib.php"), "<?php\n");

    Workspace ws = ops.createWorkspace(tmp, "klant-x");
    check(Workspace.isWorkspaceRoot(ws.getRoot()), "workspace file written");

    ops.adoptRepo(ws, "backend", existing, WorkspaceOps.Log.NONE);
    check(!Files.exists(existing), "existing checkout moved");
    check(Ticket.isGitDir(ws.mainDir().resolve("backend")), "backend in main");

    ops.addRepoByClone(ws, "frontend", remoteFrontend.toString(), WorkspaceOps.Log.NONE);
    Files.createDirectories(ws.mainDir().resolve("frontend/node_modules/.bin"));
    Files.writeString(ws.mainDir().resolve("frontend/node_modules/x.js"), "x\n");
    Files.createSymbolicLink(ws.mainDir().resolve("frontend/node_modules/.bin/x"), Path.of("../x.js"));

    ws = Workspace.load(ws.getRoot());
    eq("develop", ws.getConfig().getRepos().get("backend").getBaseBranch(), "detected backend default branch");
    eq("main", ws.getConfig().getRepos().get("frontend").getBaseBranch(), "detected frontend default branch");

    // main/.idea with settings that should and shouldn't be copied
    Path mainIdea = ws.mainDir().resolve(".idea");
    Files.createDirectories(mainIdea);
    Files.writeString(mainIdea.resolve("php.xml"), "<project/>");
    Files.writeString(mainIdea.resolve("workspace.xml"), "<project><component name=\"ProjectId\" id=\"X\"/></project>");

    // ticket touching both repos
    Ticket login = ops.createTicket(ws, "LOGIN-42", ws.getConfig().branchFor("LOGIN-42"), List.of(
        new WorkspaceOps.RepoChoice("backend", true, null),
        new WorkspaceOps.RepoChoice("frontend", true, null)), WorkspaceOps.Log.NONE);
    eq("feature/LOGIN-42", git.run(login.getDir().resolve("backend"), "branch", "--show-current").out(), "backend branch");
    eq("feature/LOGIN-42", git.run(login.getDir().resolve("frontend"), "branch", "--show-current").out(), "frontend branch");
    check(Files.isRegularFile(login.getDir().resolve("backend/.git")), "backend is a worktree (.git file)");
    eq("APP_ENV=local\n", Files.readString(login.getDir().resolve("backend/.env")), ".env copied");
    check(Files.isRegularFile(login.getDir().resolve("backend/vendor/acme/lib.php")), "vendor copied");
    check(Files.isSymbolicLink(login.getDir().resolve("frontend/node_modules/.bin/x")), "node_modules symlink kept");
    eq("klant-x \u00b7 LOGIN-42", Files.readString(login.getDir().resolve(".idea/.name"), StandardCharsets.UTF_8), "tab name");
    check(Files.isRegularFile(login.getDir().resolve(".idea/php.xml")), "php.xml copied");
    check(!Files.exists(login.getDir().resolve(".idea/workspace.xml")), "workspace.xml NOT copied");
    String vcs = Files.readString(login.getDir().resolve(".idea/vcs.xml"));
    check(vcs.contains("$PROJECT_DIR$/backend") && vcs.contains("$PROJECT_DIR$/frontend"), "vcs mappings: " + vcs);
    check(Files.isRegularFile(login.getDir().resolve(".idea/modules.xml")), "modules.xml");
    List<TicketRepo> repos = login.repos();
    eq(2, repos.size(), "repo count");
    check(repos.stream().allMatch(r -> r.kind() == TicketRepo.Kind.WORKTREE), "both worktrees");

    // the worktree branch is based on origin/develop (backend) -> same commit
    eq(git.run(ws.mainDir().resolve("backend"), "rev-parse", "origin/develop").out(),
        git.run(login.getDir().resolve("backend"), "rev-parse", "HEAD").out(), "branched from origin/develop");

    // ticket touching only the backend
    Ticket pay = ops.createTicket(ws, "PAY-7", "feature/PAY-7", List.of(
        new WorkspaceOps.RepoChoice("backend", true, null),
        new WorkspaceOps.RepoChoice("frontend", false, null)), WorkspaceOps.Log.NONE);
    TicketRepo fe = pay.repos().stream().filter(r -> r.name().equals("frontend")).findFirst().orElseThrow();
    eq(TicketRepo.Kind.ATTACHED, fe.kind(), "frontend attached");
    eq(ws.mainDir().resolve("frontend"), fe.dir(), "attached dir is main/frontend");
    String payVcs = Files.readString(pay.getDir().resolve(".idea/vcs.xml"));
    check(payVcs.contains("$PROJECT_DIR$/../main/frontend"), "attached vcs mapping: " + payVcs);
    String iml = imlOf(pay);
    check(iml.contains(IdeaFiles.fileUrl(ws.mainDir().resolve("frontend"))), "attached content root in iml: " + iml);
    IdeaFiles.prepare(pay); // idempotent
    eq(iml, imlOf(pay), "prepare is idempotent");

    // PhpStorm may rewrite the entry relative to $MODULE_DIR$ and with children: no duplicate is added
    Path imlFile = imlPath(pay);
    String rewritten = iml.replace("<content url=\"" + IdeaFiles.fileUrl(ws.mainDir().resolve("frontend")) + "\" />",
        "<content url=\"file://$MODULE_DIR$/../main/frontend\">\n      <excludeFolder url=\"file://$MODULE_DIR$/../main/frontend/node_modules\" />\n    </content>");
    check(!rewritten.equals(iml), "rewrite applied");
    Files.writeString(imlFile, rewritten);
    IdeaFiles.prepare(pay);
    eq(rewritten, imlOf(pay), "relative entry with children recognised, nothing added");

    // later the frontend joins the ticket -> content root disappears
    ops.addWorktree(ws, pay, "frontend", "feature/PAY-7", null, WorkspaceOps.Log.NONE);
    IdeaFiles.prepare(pay);
    check(!imlOf(pay).contains(IdeaFiles.fileUrl(ws.mainDir().resolve("frontend"))), "content root removed again");
    check(!imlOf(pay).contains("main/frontend"), "relative content root removed too: " + imlOf(pay));

    // a branch that already exists on the remote is tracked
    git.run(ws.mainDir().resolve("backend"), "push", "origin", "develop:refs/heads/feature/REMOTE-1").orThrow("push");
    git.run(ws.mainDir().resolve("backend"), "fetch", "origin").orThrow("fetch");
    Ticket remote = ops.createTicket(ws, "REMOTE-1", "feature/REMOTE-1", List.of(
        new WorkspaceOps.RepoChoice("backend", true, null)), WorkspaceOps.Log.NONE);
    RepoStatus rs = RepoStatus.read(git, remote.getDir().resolve("backend"));
    eq("origin/feature/REMOTE-1", rs.upstream(), "tracks remote branch");

    // tickets list: main first
    List<String> names = ws.tickets().stream().map(Ticket::getName).toList();
    eq(List.of("main", "LOGIN-42", "PAY-7", "REMOTE-1"), names, "ticket order");
    eq("LOGIN-42", ws.ticketFor(login.getDir().resolve("backend/src")).getName(), "ticketFor");
    eq(ws.getRoot(), Workspace.findContaining(login.getDir().resolve("backend")).getRoot(), "findContaining");

    // status + finish checks
    Path be = login.getDir().resolve("backend");
    Files.writeString(be.resolve("new.txt"), "hi\n");
    RepoStatus dirty = RepoStatus.read(git, be);
    eq(1, dirty.changed(), "untracked counted");
    eq("1 changed", dirty.summary(), "summary");
    git.run(be, "add", ".").orThrow("add");
    git.run(be, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "work").orThrow("commit");
    RepoStatus committed = RepoStatus.read(git, be);
    eq(0, committed.changed(), "clean after commit");
    eq(1, committed.unpushed(), "one unpushed commit");
    List<WorkspaceOps.Problem> problems = ops.checkBeforeFinish(login);
    eq(1, problems.size(), "one problem (unpushed)");

    Files.writeString(login.getDir().resolve("frontend/scratch.txt"), "x\n");
    WorkspaceOps.FinishResult notForced = ops.finishTicket(login, false, false, WorkspaceOps.Log.NONE);
    check(!notForced.folderRemoved(), "dirty ticket not removed without force");
    check(Files.isDirectory(login.getDir()), "folder kept");

    WorkspaceOps.FinishResult forced = ops.finishTicket(ws.ticket("LOGIN-42"), true, true, WorkspaceOps.Log.NONE);
    check(forced.folderRemoved(), "forced finish removes folder: " + forced.messages());
    check(!Files.exists(login.getDir()), "LOGIN-42 folder gone");
    check(forced.messages().stream().anyMatch(m -> m.contains("kept branch feature/LOGIN-42")),
        "unmerged branch kept: " + forced.messages());
    check(git.run(ws.mainDir().resolve("frontend"), "show-ref", "--verify", "--quiet", "refs/heads/feature/LOGIN-42").ok() == false,
        "merged (no new commits) frontend branch deleted");
    String wtList = git.run(ws.mainDir().resolve("backend"), "worktree", "list").out();
    check(!wtList.contains("LOGIN-42"), "worktree pruned: " + wtList);

    // a non-ticket folder is not listed, and finishing never deletes unknown files
    Files.createDirectories(ws.getRoot().resolve("docs"));
    Files.writeString(ws.getRoot().resolve("docs/notes.md"), "keep me\n");
    check(ws.tickets().stream().noneMatch(t -> t.getName().equals("docs")), "docs folder is not a ticket");
    WorkspaceOps.FinishResult docs = ops.finishTicket(ws.ticket("docs"), true, false, WorkspaceOps.Log.NONE);
    check(!docs.folderRemoved() && Files.exists(ws.getRoot().resolve("docs/notes.md")), "docs kept: " + docs.messages());
    Files.writeString(pay.getDir().resolve("NOTES.txt"), "mine\n");
    check(ops.checkBeforeFinish(pay).stream().anyMatch(p -> p.text().contains("NOTES.txt")), "leftover reported");
    WorkspaceOps.FinishResult payDone = ops.finishTicket(pay, true, false, WorkspaceOps.Log.NONE);
    check(!payDone.folderRemoved() && Files.exists(pay.getDir().resolve("NOTES.txt")), "notes kept: " + payDone.messages());
    try {
      ops.finishTicket(ws.ticket("MAIN"), true, true, WorkspaceOps.Log.NONE);
      throw new AssertionError("MAIN (case variant) finished");
    } catch (IOException expected) {
      // ok
    }

    // main prepare generates a module
    IdeaFiles.prepare(ws.ticket("main"));
    check(Files.isRegularFile(mainIdea.resolve("modules.xml")), "main modules.xml");
    eq("klant-x \u00b7 main", Files.readString(mainIdea.resolve(".name")), "main tab name");
  }

  static void guards() throws Exception {
    Path tmp = Files.createTempDirectory("guards");
    WorkspaceOps ops = new WorkspaceOps(GitRunner.fromSystem());
    Workspace ws = ops.createWorkspace(tmp, "w");
    for (String bad : List.of("", "a b", "../x", ".hidden", "main")) {
      try {
        ops.createTicket(ws, bad, "b", List.of(new WorkspaceOps.RepoChoice("x", true, null)), WorkspaceOps.Log.NONE);
        throw new AssertionError("accepted bad ticket name '" + bad + "'");
      } catch (IOException expected) {
        // ok
      }
    }
    try {
      ops.finishTicket(ws.ticket("main"), true, true, WorkspaceOps.Log.NONE);
      throw new AssertionError("main finished");
    } catch (IOException expected) {
      // ok
    }
    check(Files.isDirectory(ws.mainDir()), "main still there");
    try {
      ops.createWorkspace(tmp, "w");
      throw new AssertionError("workspace created twice");
    } catch (IOException expected) {
      // ok
    }
  }

  // --------------------------------------------------------------- helpers

  private static Path imlPath(Ticket t) throws IOException {
    try (var s = Files.list(t.getDir().resolve(".idea"))) {
      return s.filter(p -> p.toString().endsWith(".iml")).findFirst().orElseThrow();
    }
  }

  private static String imlOf(Ticket t) throws IOException {
    try (var s = Files.list(t.getDir().resolve(".idea"))) {
      Path iml = s.filter(p -> p.toString().endsWith(".iml")).findFirst().orElseThrow();
      return Files.readString(iml);
    }
  }

  private static Path bareRepoWithCommit(GitRunner git, Path tmp, String name, String branch) throws Exception {
    Path work = tmp.resolve(name + "-seed");
    git.run(tmp, "init", "-q", "-b", branch, work.toString()).orThrow("init");
    Files.writeString(work.resolve(".gitignore"), ".env\nvendor/\nnode_modules/\n");
    Files.writeString(work.resolve("README.md"), "# " + name + "\n");
    git.run(work, "add", ".").orThrow("add");
    git.run(work, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init").orThrow("commit");
    Path bare = tmp.resolve(name + ".git");
    git.run(tmp, "clone", "-q", "--bare", work.toString(), bare.toString()).orThrow("bare");
    git.run(bare, "symbolic-ref", "HEAD", "refs/heads/" + branch).orThrow("head");
    return bare;
  }
}
