package nl.netonic.workspaces.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
    test("ticket keys with '/', saved branch, branch-from refs", CoreTests::slashKeysAndBases);
    test("links next to the workspace", CoreTests::links);
    test("options: links off, default copy list; clone name suggestions", CoreTests::optionsAndCloneNames);
    test("delete never follows symlinks", CoreTests::deleteKeepsLinkTargets);
    test("workspace named like the checkout it adopts", CoreTests::workspaceNamedLikeCheckout);
    test("single-repo workspace: the repo is main/ itself", CoreTests::singleRepoWorkspace);
    test("undo workspace: repos back out, workspace gone", CoreTests::undoWorkspace);
    test("undo single-repo workspace named like its checkout", CoreTests::undoSingleRepoWorkspace);

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
    eq(Map.of("a", 1.0), Json.parse("\uFEFF{\"a\": 1}"), "byte-order mark skipped");
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

    // fields from a newer version survive a save by this one; unsafe repo names are ignored
    Files.writeString(f, """
        { "name": "n", "db": {"perTicket": true},
          "repos": { "backend": { "baseBranch": "develop", "setup": ["composer install"] }, "../evil": {} } }
        """);
    WorkspaceConfig c3 = WorkspaceConfig.load(f);
    eq(List.of("backend"), new ArrayList<>(c3.getRepos().keySet()), "unsafe repo name skipped");
    c3.save(f);
    Map<?, ?> saved = (Map<?, ?>) Json.parse(Files.readString(f));
    eq(Map.of("perTicket", true), saved.get("db"), "unknown top-level field kept");
    eq(List.of("composer install"), ((Map<?, ?>) ((Map<?, ?>) saved.get("repos")).get("backend")).get("setup"),
        "unknown repo field kept");
    check(!Files.exists(dir.resolve(WorkspaceConfig.FILE_NAME + ".tmp")), "no temp file left");

    // a ticket's branch is kept per folder, with fields a newer version added
    Files.writeString(f, """
        { "name": "n", "tickets": { "A-1-x": { "branch": "feature/A-1/x", "notes": "hi" }, "../evil": {} } }
        """);
    WorkspaceConfig c4 = WorkspaceConfig.load(f);
    eq("feature/A-1/x", c4.ticketBranch("A-1-x"), "saved ticket branch");
    eq("feature/B-2", c4.ticketBranch("B-2"), "unsaved ticket falls back to the pattern");
    c4.setTicketBranch("B-2", "bugfix/B-2/y");
    c4.save(f);
    WorkspaceConfig c5 = WorkspaceConfig.load(f);
    eq("bugfix/B-2/y", c5.ticketBranch("B-2"), "ticket branch saved");
    Map<?, ?> tickets = (Map<?, ?>) ((Map<?, ?>) Json.parse(Files.readString(f))).get("tickets");
    eq(Map.of("branch", "feature/A-1/x", "notes", "hi"), tickets.get("A-1-x"), "unknown ticket field kept");
    check(!tickets.containsKey("../evil"), "unsafe ticket name skipped");
    c5.removeTicket("A-1-x");
    c5.removeTicket("B-2");
    c5.save(f);
    check(!((Map<?, ?>) Json.parse(Files.readString(f))).containsKey("tickets"), "empty tickets section not written");
  }

  static void porcelainParse() {
    RepoStatus s = RepoStatus.parse("""
        # branch.oid abc
        # branch.head feature/X
        # branch.upstream origin/feature/X
        # branch.ab +2 -1
        1 .M N... 100644 100644 100644 a b file.txt
        ? new.txt
        ? .idea/
        ? .DS_Store
        ? sub/.DS_Store
        """);
    eq("feature/X", s.branch(), "branch");
    eq("origin/feature/X", s.upstream(), "upstream");
    eq(2, s.ahead(), "ahead");
    eq(1, s.behind(), "behind");
    eq(2, s.changed(), "changed, untracked .idea and .DS_Store not counted");
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
    check(Files.isSymbolicLink(existing), "existing checkout moved, a link left at the old path");
    eq(ws.mainDir().resolve("backend"), Links.resolve(existing), "old path links to main/backend");
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
    check(Files.isDirectory(login.getDir().resolve("backend")), "all-or-nothing: clean backend worktree kept too");

    WorkspaceOps.FinishResult forced = ops.finishTicket(ws.ticket("LOGIN-42"), true, true, WorkspaceOps.Log.NONE);
    check(forced.folderRemoved(), "forced finish removes folder: " + forced.messages());
    check(!Files.exists(login.getDir()), "LOGIN-42 folder gone");
    check(forced.messages().stream().anyMatch(m -> m.contains("kept branch feature/LOGIN-42")),
        "unmerged branch kept: " + forced.messages());
    check(git.run(ws.mainDir().resolve("frontend"), "show-ref", "--verify", "--quiet", "refs/heads/feature/LOGIN-42").ok() == false,
        "merged (no new commits) frontend branch deleted");
    String wtList = git.run(ws.mainDir().resolve("backend"), "worktree", "list").out();
    check(!wtList.contains("LOGIN-42"), "worktree pruned: " + wtList);

    // branch names git would read as an option are rejected before anything runs
    for (String bad : List.of("--orphan", "a..b")) {
      try {
        ops.addWorktree(ws, ws.ticket("BAD-1"), "backend", bad, null, WorkspaceOps.Log.NONE);
        throw new AssertionError("accepted branch '" + bad + "'");
      } catch (GitException expected) {
        // ok
      }
    }
    check(!Files.exists(ws.getRoot().resolve("BAD-1")), "nothing created for a bad branch");

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

    // a clone in another workspace's main/ is never moved
    Path otherClone = ws.mainDir().resolve("r");
    GitRunner.fromSystem().run(tmp, "init", "-q", otherClone.toString()).orThrow("init");
    Workspace w2 = ops.createWorkspace(tmp, "w2");
    try {
      ops.adoptRepo(w2, "r", otherClone, WorkspaceOps.Log.NONE);
      throw new AssertionError("adopted a repo from another workspace");
    } catch (IOException expected) {
      // ok
    }
    check(Files.isDirectory(otherClone.resolve(".git")), "other workspace's clone untouched");
  }

  static void workspaceNamedLikeCheckout() throws Exception {
    Path tmp = Files.createTempDirectory("samename");
    GitRunner git = GitRunner.fromSystem();
    WorkspaceOps ops = new WorkspaceOps(git);
    Path checkout = tmp.resolve("netcaly");
    git.run(tmp, "init", "-q", checkout.toString()).orThrow("init");
    Files.writeString(checkout.resolve("index.php"), "<?php\n");
    Path other = tmp.resolve("other");
    git.run(tmp, "init", "-q", other.toString()).orThrow("init");

    eq(other, ops.moveAsideForWorkspace(tmp, "netcaly", other, WorkspaceOps.Log.NONE), "other folder left alone");
    Path link = tmp.resolve("link");
    Files.createSymbolicLink(link, checkout);
    eq(checkout, ops.moveAsideForWorkspace(tmp, "link", checkout, WorkspaceOps.Log.NONE), "link at the root: not moved");

    // macOS: "Netcaly" is the same folder as "netcaly"; Linux: a different, free name
    boolean caseInsensitive = Files.exists(tmp.resolve("NETCALY"));
    String name = caseInsensitive ? "Netcaly" : "netcaly";
    Path aside = ops.moveAsideForWorkspace(tmp, name, checkout, WorkspaceOps.Log.NONE);
    check(!aside.equals(checkout) && Files.isRegularFile(aside.resolve("index.php")), "checkout moved aside");
    check(!Files.exists(checkout), "name is free");
    Workspace ws = ops.createWorkspace(tmp, name);
    ops.adoptRepo(ws, "frontend", aside, WorkspaceOps.Log.NONE);
    check(Files.isRegularFile(ws.mainDir().resolve("frontend/index.php")), "checkout in main/frontend");
    check(!Files.exists(aside, LinkOption.NOFOLLOW_LINKS), "nothing left aside");
    eq(name, tmp.toRealPath().resolve(name).toRealPath().getFileName().toString(), "workspace folder has the new name");
  }

  static void singleRepoWorkspace() throws Exception {
    Path tmp = Files.createTempDirectory("single");
    GitRunner git = GitRunner.fromSystem();
    WorkspaceOps ops = new WorkspaceOps(git);
    Path remote = bareRepoWithCommit(git, tmp, "remote-solo", "main");

    Workspace ws = ops.createWorkspace(tmp, "solo");
    check(!ws.isSingleRepo(), "empty workspace is not single-repo yet");
    ops.addRepoByClone(ws, null, remote.toString(), WorkspaceOps.Log.NONE);
    ws = Workspace.load(ws.getRoot());
    check(ws.isSingleRepo(), "main/ is the clone");
    check(Files.isRegularFile(ws.mainDir().resolve("README.md")), "no repo subfolder in main/");
    eq(List.of("solo"), List.copyOf(ws.getConfig().getRepos().keySet()), "registered under the workspace's name");
    eq("main", ws.getConfig().getRepos().get("solo").getBaseBranch(), "base branch detected");
    eq(List.of(new TicketRepo("solo", ws.mainDir(), TicketRepo.Kind.CLONE)), ws.ticket(Workspace.MAIN).repos(), "main");
    eq(ws.mainDir(), Links.resolve(tmp.resolve("solo-main")), "main linked as solo-main");
    try {
      ops.addRepoByClone(ws, "other", remote.toString(), WorkspaceOps.Log.NONE);
      throw new AssertionError("second repo added to a single-repo workspace");
    } catch (IOException expected) {
      // ok
    }

    Files.writeString(ws.mainDir().resolve(".env"), "APP_ENV=local\n");
    Ticket t = ops.createTicket(ws, "T-1", "feature/T-1",
        List.of(new WorkspaceOps.RepoChoice("solo", true, null)), WorkspaceOps.Log.NONE);
    check(Files.isRegularFile(t.getDir().resolve(".git")), "ticket folder is the worktree");
    check(Files.isRegularFile(t.getDir().resolve(".env")), ".env copied");
    eq(List.of(new TicketRepo("solo", t.getDir(), TicketRepo.Kind.WORKTREE)), t.repos(), "ticket repos");
    eq(List.of("main", "T-1"), ws.tickets().stream().map(Ticket::getName).toList(), "tickets");
    check(Files.readString(t.getDir().resolve(".idea/vcs.xml")).contains("directory=\"$PROJECT_DIR$\""), "vcs root");
    eq(t.getDir(), Links.resolve(tmp.resolve("solo-t-1")), "ticket linked as solo-t-1");
    eq("feature/T-1", RepoStatus.read(git, t.getDir()).branch(), "on the ticket branch");
    eq(0, RepoStatus.read(git, t.getDir()).changed(), "untracked .idea is not a change");
    eq(List.of(), ops.checkBeforeFinish(t), "nothing in the way of finishing");

    WorkspaceOps.FinishResult r = ops.finishTicket(t, false, true, WorkspaceOps.Log.NONE);
    check(r.folderRemoved(), "ticket removed: " + r.messages());
    check(!Files.exists(t.getDir()), "ticket folder gone");
    check(!Files.exists(tmp.resolve("solo-t-1"), LinkOption.NOFOLLOW_LINKS), "ticket link gone");
    check(Files.isRegularFile(ws.mainDir().resolve("README.md")), "main untouched");

    // uncommitted work still blocks Finish
    Ticket t2 = ops.createTicket(ws, "T-2", "feature/T-2",
        List.of(new WorkspaceOps.RepoChoice("solo", true, null)), WorkspaceOps.Log.NONE);
    Files.writeString(t2.getDir().resolve("work.php"), "<?php\n");
    check(!ops.finishTicket(t2, false, true, WorkspaceOps.Log.NONE).folderRemoved(), "dirty ticket kept");
    check(Files.isRegularFile(t2.getDir().resolve("work.php")), "work kept");

    // an existing checkout as the only repo
    Path checkout = tmp.resolve("solo2-checkout");
    git.run(tmp, "clone", "-q", remote.toString(), checkout.toString()).orThrow("clone");
    Workspace ws2 = ops.createWorkspace(tmp, "solo2");
    ops.adoptRepo(ws2, null, checkout, WorkspaceOps.Log.NONE);
    check(Workspace.load(ws2.getRoot()).isSingleRepo(), "adopted checkout is main/");

    // a workspace that already has a repo in main/<name> can't take one in main/ itself
    Workspace ws3 = ops.createWorkspace(tmp, "multi");
    ops.addRepoByClone(ws3, "api", remote.toString(), WorkspaceOps.Log.NONE);
    try {
      ops.addRepoByClone(ws3, null, remote.toString(), WorkspaceOps.Log.NONE);
      throw new AssertionError("repo cloned into a non-empty main/");
    } catch (IOException expected) {
      // ok
    }
    check(!ws3.isSingleRepo(), "multi stays multi");
  }

  static void undoWorkspace() throws Exception {
    Path tmp = Files.createTempDirectory("undo");
    GitRunner git = GitRunner.fromSystem();
    WorkspaceOps ops = new WorkspaceOps(git);
    Path remoteBackend = bareRepoWithCommit(git, tmp, "remote-backend", "main");
    Path remoteFrontend = bareRepoWithCommit(git, tmp, "remote-frontend", "main");
    Path projects = Files.createDirectories(tmp.resolve("projects"));
    Path existing = projects.resolve("old-backend");
    git.run(tmp, "clone", "-q", remoteBackend.toString(), existing.toString()).orThrow("clone");

    Workspace ws = ops.createWorkspace(projects, "kx");
    ops.adoptRepo(ws, "backend", existing, WorkspaceOps.Log.NONE);
    ops.addRepoByClone(ws, "frontend", remoteFrontend.toString(), WorkspaceOps.Log.NONE);
    ws = Workspace.load(ws.getRoot());
    eq(existing.toString(), ws.getConfig().getRepos().get("backend").getAdoptedFrom(), "adopted path saved");
    eq(existing.toString(), WorkspaceConfig.load(ws.configFile()).getRepos().get("backend").getAdoptedFrom(),
        "adopted path on disk");
    eq(Map.of("backend", existing, "frontend", projects.resolve("kx-frontend")), WorkspaceOps.undoDestinations(ws),
        "destinations");

    List<WorkspaceOps.RepoChoice> both = List.of(new WorkspaceOps.RepoChoice("backend", true, null),
        new WorkspaceOps.RepoChoice("frontend", true, null));
    Ticket a = ops.createTicket(ws, "A-1", "feature/A-1", both, WorkspaceOps.Log.NONE);
    // an unpushed commit: the branch must survive the undo
    Files.writeString(a.getDir().resolve("backend/a.php"), "<?php\n");
    git.run(a.getDir().resolve("backend"), "add", ".").orThrow("add");
    git.run(a.getDir().resolve("backend"), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "a")
        .orThrow("commit");
    Ticket b = ops.createTicket(ws, "B-2", "feature/B-2", both.subList(1, 2), WorkspaceOps.Log.NONE);
    Files.writeString(b.getDir().resolve("frontend/wip.js"), "x\n");

    eq(1, ops.checkBeforeUndo(ws).size(), "uncommitted work found");
    try {
      ops.undoWorkspace(ws, WorkspaceOps.Log.NONE);
      throw new AssertionError("undone with uncommitted work");
    } catch (IOException expected) {
      // ok
    }
    check(Files.isRegularFile(b.getDir().resolve("frontend/wip.js")), "nothing touched");
    check(Ticket.isGitDir(ws.mainDir().resolve("backend")), "main untouched");
    Files.delete(b.getDir().resolve("frontend/wip.js"));

    WorkspaceOps.UndoResult r = ops.undoWorkspace(ws, WorkspaceOps.Log.NONE);
    check(r.folderRemoved(), "workspace folder removed: " + r.messages());
    check(!Files.exists(ws.getRoot()), "kx/ gone");
    eq(existing, r.repos().get("backend"), "backend back where it came from");
    check(Ticket.isGitDir(existing) && Files.isDirectory(existing.resolve(".git")), "backend is a plain clone again");
    eq(projects.resolve("kx-frontend"), r.repos().get("frontend"), "frontend next to where the workspace was");
    check(git.run(existing, "show-ref", "--verify", "--quiet", "refs/heads/feature/A-1").ok(), "ticket branch kept");
    eq(1L, git.run(existing, "worktree", "list", "--porcelain").out().lines().filter(l -> l.startsWith("worktree "))
        .count(), "no worktrees left besides the clone itself");
    try (var left = Files.list(projects)) {
      eq(List.of("kx-frontend", "old-backend"), left.map(p -> p.getFileName().toString()).sorted().toList(),
          "no links or leftovers next to it");
    }
  }

  static void undoSingleRepoWorkspace() throws Exception {
    Path tmp = Files.createTempDirectory("undo1");
    GitRunner git = GitRunner.fromSystem();
    WorkspaceOps ops = new WorkspaceOps(git);
    Path remote = bareRepoWithCommit(git, tmp, "remote", "main");
    Path projects = Files.createDirectories(tmp.resolve("projects"));
    Path checkout = projects.resolve("samey");
    git.run(tmp, "clone", "-q", remote.toString(), checkout.toString()).orThrow("clone");

    // as New Workspace does it: the checkout is in the way of the workspace folder
    Path aside = ops.moveAsideForWorkspace(projects, "samey", checkout, WorkspaceOps.Log.NONE);
    Workspace ws = ops.createWorkspace(projects, "samey");
    ops.adoptRepo(ws, null, aside, checkout, WorkspaceOps.Log.NONE);
    ws = Workspace.load(ws.getRoot());
    check(ws.isSingleRepo(), "single");
    ops.createTicket(ws, "T-1", "feature/T-1", List.of(new WorkspaceOps.RepoChoice("samey", true, null)),
        WorkspaceOps.Log.NONE);

    WorkspaceOps.UndoResult r = ops.undoWorkspace(ws, WorkspaceOps.Log.NONE);
    check(r.folderRemoved(), "removed: " + r.messages());
    eq(checkout, r.repos().get("samey"), "back at its own path");
    check(Files.isDirectory(checkout.resolve(".git")) && Files.isRegularFile(checkout.resolve("README.md")),
        "a plain checkout again");
    check(!Workspace.isWorkspaceRoot(checkout), "no .workspace.json");
    try (var left = Files.list(projects)) {
      eq(List.of("samey"), left.map(p -> p.getFileName().toString()).toList(), "nothing else left");
    }

    // a file of the user's in the workspace folder: the folder stays, the repo goes next to it
    Path c2 = projects.resolve("other");
    git.run(tmp, "clone", "-q", remote.toString(), c2.toString()).orThrow("clone");
    Workspace w2 = ops.createWorkspace(projects, "w2");
    ops.adoptRepo(w2, null, c2, WorkspaceOps.Log.NONE);
    Files.writeString(w2.getRoot().resolve("notes.md"), "mine\n");
    WorkspaceOps.UndoResult r2 = ops.undoWorkspace(Workspace.load(w2.getRoot()), WorkspaceOps.Log.NONE);
    check(!r2.folderRemoved(), "folder kept");
    check(Files.isRegularFile(w2.getRoot().resolve("notes.md")), "notes kept");
    check(!Workspace.isWorkspaceRoot(w2.getRoot()), "no longer a workspace");
    eq(c2, r2.repos().get("w2"), "adopted repo back at its path");
  }

  static void slashKeysAndBases() throws Exception {
    for (String ok : List.of("LOGIN-42", "LOGIN-42/login-form", "a/b/c")) {
      check(Workspace.isValidTicketKey(ok), "valid key " + ok);
    }
    for (String bad : List.of("", "/a", "a/", "a//b", "a/../b", "a/.x", "a b/c", "a\\b")) {
      check(!Workspace.isValidTicketKey(bad), "invalid key '" + bad + "'");
    }
    eq("LOGIN-42-login-form", Workspace.ticketFolderName("LOGIN-42/login-form"), "folder name");

    Path tmp = Files.createTempDirectory("wsbases");
    GitRunner git = GitRunner.fromSystem();
    WorkspaceOps ops = new WorkspaceOps(git);
    Path remoteRepo = bareRepoWithCommit(git, tmp, "remote-app", "develop");
    Workspace ws = ops.createWorkspace(tmp, "w");
    ops.addRepoByClone(ws, "app", remoteRepo.toString(), WorkspaceOps.Log.NONE);
    Path app = ws.mainDir().resolve("app");

    // local develop gets a commit origin doesn't have; origin gets a branch not checked out locally
    Files.writeString(app.resolve("local.txt"), "x\n");
    git.run(app, "add", ".").orThrow("add");
    git.run(app, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "local").orThrow("commit");
    git.run(app, "push", "origin", "origin/develop:refs/heads/release/1.0").orThrow("push");
    git.run(app, "fetch", "origin").orThrow("fetch");
    String localDevelop = git.run(app, "rev-parse", "refs/heads/develop").out();
    String originDevelop = git.run(app, "rev-parse", "refs/remotes/origin/develop").out();
    check(!localDevelop.equals(originDevelop), "local develop is ahead");

    WorkspaceOps.Branches branches = ops.listBranches(app);
    eq(List.of("refs/heads/develop"), branches.local(), "local branches");
    eq(List.of("refs/remotes/origin/develop", "refs/remotes/origin/release/1.0"), branches.remote(),
        "remote branches, without origin/HEAD");

    // the dialog turns LOGIN-42/login-form into this folder and branch
    String folder = Workspace.ticketFolderName("LOGIN-42/login-form");
    Ticket slash = ops.createTicket(ws, folder, ws.getConfig().branchFor("LOGIN-42/login-form"), List.of(
        new WorkspaceOps.RepoChoice("app", true, "refs/remotes/origin/develop")), WorkspaceOps.Log.NONE);
    eq(tmp.resolve("w/LOGIN-42-login-form"), slash.getDir(), "ticket folder");
    eq("feature/LOGIN-42/login-form", git.run(slash.getDir().resolve("app"), "branch", "--show-current").out(), "branch");
    eq(originDevelop, git.run(slash.getDir().resolve("app"), "rev-parse", "HEAD").out(), "picked origin/develop");
    eq("w \u00b7 LOGIN-42-login-form", Files.readString(slash.getDir().resolve(".idea/.name"), StandardCharsets.UTF_8),
        "tab name");
    eq("feature/LOGIN-42/login-form", WorkspaceConfig.load(ws.configFile()).ticketBranch(folder), "branch saved");
    eq("feature/LOGIN-42/login-form", ws.getConfig().ticketBranch(folder), "branch saved in memory too");

    Ticket local = ops.createTicket(ws, "L-1", "feature/L-1", List.of(
        new WorkspaceOps.RepoChoice("app", true, "refs/heads/develop")), WorkspaceOps.Log.NONE);
    eq(localDevelop, git.run(local.getDir().resolve("app"), "rev-parse", "HEAD").out(), "picked local develop as-is");

    Ticket plain = ops.createTicket(ws, "P-1", "feature/P-1", List.of(
        new WorkspaceOps.RepoChoice("app", true, "develop")), WorkspaceOps.Log.NONE);
    eq(localDevelop, git.run(plain.getDir().resolve("app"), "rev-parse", "HEAD").out(), "plain name: local first");

    Ticket remoteOnly = ops.createTicket(ws, "R-1", "feature/R-1", List.of(
        new WorkspaceOps.RepoChoice("app", true, "release/1.0")), WorkspaceOps.Log.NONE);
    eq(originDevelop, git.run(remoteOnly.getDir().resolve("app"), "rev-parse", "HEAD").out(),
        "plain name without a local branch: origin's");

    for (String missing : List.of("refs/heads/nope", "refs/remotes/origin/nope", "refs/remotes/upstream/develop")) {
      try {
        ops.createTicket(ws, "X-1", "feature/X-1", List.of(
            new WorkspaceOps.RepoChoice("app", true, missing)), WorkspaceOps.Log.NONE);
        throw new AssertionError("branched from missing " + missing);
      } catch (GitException expected) {
        check(!Files.exists(ws.getRoot().resolve("X-1")), "nothing left for " + missing);
      }
    }

    WorkspaceOps.FinishResult done = ops.finishTicket(slash, true, false, WorkspaceOps.Log.NONE);
    check(done.folderRemoved(), "slash ticket finished: " + done.messages());
    eq("feature/" + folder, WorkspaceConfig.load(ws.configFile()).ticketBranch(folder), "saved branch removed on finish");
  }

  static void links() throws Exception {
    Path tmp = Files.createTempDirectory("wslinks");
    GitRunner git = GitRunner.fromSystem();
    WorkspaceOps ops = new WorkspaceOps(git);
    Path remoteApi = bareRepoWithCommit(git, tmp, "remote-api", "main");
    Path remoteWeb = bareRepoWithCommit(git, tmp, "remote-web", "main");
    Workspace ws = ops.createWorkspace(tmp, "Klant_X");

    // something the user already has under a link's name is never touched
    Path taken = Files.createDirectories(tmp.resolve("klant-x-web"));
    Files.writeString(taken.resolve("mine.txt"), "keep\n");

    List<String> warnings = new ArrayList<>();
    WorkspaceOps.Log log = new WorkspaceOps.Log() {
      @Override public void step(String text) {}
      @Override public void warn(String text) { warnings.add(text); }
    };
    ops.addRepoByClone(ws, "api", remoteApi.toString(), log);
    ops.addRepoByClone(ws, "web", remoteWeb.toString(), log);
    eq("klant-x-api", Links.name(ws.ticket(Workspace.MAIN), "api"), "main link name: lowercase, '_' becomes '-'");
    check(Files.isSymbolicLink(tmp.resolve("klant-x-api")), "main api linked");
    eq(ws.mainDir().resolve("api"), Links.resolve(tmp.resolve("klant-x-api")), "points at main/api");
    check(!Links.resolve(tmp.resolve("klant-x-api")).equals(Files.readSymbolicLink(tmp.resolve("klant-x-api"))),
        "stored relative");
    check(!Files.isSymbolicLink(taken) && Files.exists(taken.resolve("mine.txt")), "existing folder untouched");
    check(warnings.stream().anyMatch(w -> w.contains("klant-x-web") && w.contains("already exists")), "warned: " + warnings);

    Ticket t = ops.createTicket(ws, Workspace.ticketFolderName("LOGIN-42/form"), "feature/LOGIN-42/form", List.of(
        new WorkspaceOps.RepoChoice("api", true, null),
        new WorkspaceOps.RepoChoice("web", false, null)), log);
    Path apiLink = tmp.resolve("klant-x-login-42-form-api");
    check(Files.isSymbolicLink(apiLink), "ticket worktree linked");
    eq(t.getDir().resolve("api"), Links.resolve(apiLink), "points at the worktree");
    check(Files.isRegularFile(apiLink.resolve("README.md")), "readable through the link");
    check(!Files.exists(tmp.resolve("klant-x-login-42-form-web"), java.nio.file.LinkOption.NOFOLLOW_LINKS),
        "attached repo not linked");

    // adding the repo to the ticket later links it too
    ops.addWorktree(ws, t, "web", "feature/LOGIN-42/form", null, log);
    check(Files.isSymbolicLink(tmp.resolve("klant-x-login-42-form-web")), "added worktree linked");

    // a link is never adopted (that would move the link, not the checkout)
    Workspace other = ops.createWorkspace(tmp, "other");
    try {
      ops.adoptRepo(other, "api", apiLink, log);
      throw new AssertionError("adopted a link");
    } catch (IOException expected) {
      // ok
    }

    // workspaces made before links existed get them with syncLinks; existing links are left alone
    Files.delete(apiLink);
    eq(List.of("klant-x-login-42-form-api"), ops.syncLinks(ws, log).created(), "missing link recreated");
    eq(new WorkspaceOps.LinkChanges(List.of(), List.of()), ops.syncLinks(ws, log), "nothing to do the second time");

    // finish removes the ticket's links, never their targets, never main's links or other files
    WorkspaceOps.FinishResult done = ops.finishTicket(t, true, false, log);
    check(done.folderRemoved(), "finished: " + done.messages());
    check(!Files.exists(apiLink, java.nio.file.LinkOption.NOFOLLOW_LINKS), "ticket api link removed");
    check(!Files.exists(tmp.resolve("klant-x-login-42-form-web"), java.nio.file.LinkOption.NOFOLLOW_LINKS),
        "ticket web link removed");
    check(Files.isSymbolicLink(tmp.resolve("klant-x-api")) && Files.isDirectory(ws.mainDir().resolve("api/.git")),
        "main link and main checkout kept");
    check(Files.exists(taken.resolve("mine.txt")), "user's folder still untouched");

    // a link with the ticket's name that points elsewhere is not removed
    Ticket t2 = ops.createTicket(ws, "T-2", "feature/T-2", List.of(new WorkspaceOps.RepoChoice("api", true, null)), log);
    Path t2Link = tmp.resolve("klant-x-t-2-api");
    Files.delete(t2Link);
    Files.createSymbolicLink(t2Link, taken);
    ops.finishTicket(t2, true, false, log);
    check(Files.isSymbolicLink(t2Link), "foreign link kept");

    // a ticket deleted outside the plugin: the next sync removes its links, and nothing else
    Ticket t3 = ops.createTicket(ws, "T-3", "feature/T-3", List.of(new WorkspaceOps.RepoChoice("api", true, null)), log);
    Path t3Link = tmp.resolve("klant-x-t-3-api");
    check(Files.isSymbolicLink(t3Link), "T-3 linked");
    Path gone = Files.createDirectories(tmp.resolve("gone"));
    Path userLink = tmp.resolve("klant-x-notes");          // named like ours, but points outside the workspace
    Files.createSymbolicLink(userLink, gone);
    Path otherLink = tmp.resolve("mine-api");              // points into the workspace, but not named like ours
    Files.createSymbolicLink(otherLink, ws.getRoot().resolve("T-3/api"));
    git.run(ws.mainDir().resolve("api"), "worktree", "remove", "--force", t3.getDir().resolve("api").toString())
        .orThrow("remove");
    FileCloner.deleteRecursively(t3.getDir());
    Files.delete(gone);
    WorkspaceOps.LinkChanges tidy = ops.syncLinks(ws, log);
    eq(List.of("klant-x-t-3-api"), tidy.removed(), "dangling ticket link removed");
    check(Files.isSymbolicLink(userLink) && Files.isSymbolicLink(otherLink), "links that aren't ours kept");
    check(Files.isSymbolicLink(tmp.resolve("klant-x-api")), "main link kept");
    check(Files.isDirectory(ws.mainDir().resolve("api/.git")), "main checkout kept");
  }

  static void optionsAndCloneNames() throws Exception {
    eq(new Workspace.CloneNames("billebly", "api"), Workspace.suggestNames("billebly-api"), "split at last '-'");
    eq(new Workspace.CloneNames("klant-x", "backend"), Workspace.suggestNames("klant-x-backend"), "only the last '-'");
    eq(new Workspace.CloneNames("my", "repo"), Workspace.suggestNames("my_repo"), "'_' too");
    eq(new Workspace.CloneNames("api.nightcore.com", "api.nightcore.com"), Workspace.suggestNames("api.nightcore.com"),
        "no '-': folder name for both");
    eq(new Workspace.CloneNames("-x", "-x"), Workspace.suggestNames("-x"), "leading '-' not split");

    Path tmp = Files.createTempDirectory("wsoptions");
    GitRunner git = GitRunner.fromSystem();
    Path remote = bareRepoWithCommit(git, tmp, "remote-app", "main");
    WorkspaceOps withLinks = new WorkspaceOps(git);
    WorkspaceOps noLinks = new WorkspaceOps(git, new WorkspaceOps.Options(false, List.of(".env")));
    Workspace ws = noLinks.createWorkspace(tmp, "w");
    noLinks.addRepoByClone(ws, "app", remote.toString(), WorkspaceOps.Log.NONE);
    check(!Files.exists(tmp.resolve("w-app"), java.nio.file.LinkOption.NOFOLLOW_LINKS), "links off: no link on clone");
    eq(List.of(".env"), WorkspaceConfig.load(ws.configFile()).getRepos().get("app").getCopy(), "default copy list used");
    Ticket t = noLinks.createTicket(ws, "T-1", "feature/T-1", List.of(new WorkspaceOps.RepoChoice("app", true, null)),
        WorkspaceOps.Log.NONE);
    check(!Files.exists(tmp.resolve("w-t-1-app"), java.nio.file.LinkOption.NOFOLLOW_LINKS), "links off: no ticket link");
    eq(List.of(), noLinks.syncLinks(ws, WorkspaceOps.Log.NONE).created(), "links off: sync creates nothing");

    // switched on: the scan makes them; switched off again: the scan removes them (and only ours)
    eq(List.of("w-app", "w-t-1-app"), withLinks.syncLinks(ws, WorkspaceOps.Log.NONE).created(), "links on: created");
    Path mine = tmp.resolve("w-mine");
    Files.createSymbolicLink(mine, tmp.resolve("remote-app.git"));
    eq(List.of("w-app", "w-t-1-app"), noLinks.syncLinks(ws, WorkspaceOps.Log.NONE).removed().stream().sorted().toList(),
        "links off: removed");
    check(Files.isSymbolicLink(mine), "link pointing outside the workspace kept");
    check(Files.isDirectory(ws.mainDir().resolve("app/.git")) && Files.isDirectory(t.getDir().resolve("app")),
        "targets kept");
  }

  static void deleteKeepsLinkTargets() throws Exception {
    Path tmp = Files.createTempDirectory("links");
    Path outside = Files.createDirectories(tmp.resolve("outside"));
    Files.writeString(outside.resolve("precious.txt"), "keep\n");
    Path ticket = Files.createDirectories(tmp.resolve("T-1/.idea"));
    Files.createSymbolicLink(ticket.resolve("linked-dir"), outside);
    Files.createSymbolicLink(ticket.resolve("linked-file"), outside.resolve("precious.txt"));
    FileCloner.deleteRecursively(tmp.resolve("T-1"));
    check(!Files.exists(tmp.resolve("T-1")), "ticket folder removed");
    check(Files.isRegularFile(outside.resolve("precious.txt")), "symlink target kept");
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
